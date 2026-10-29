package com.quanxiaoha.xiaohashu.note.biz.service.impl;

import com.quanxiaoha.framework.common.response.PageResponse;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.RandomUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.base.Preconditions;
import com.quanxiaoha.framework.biz.context.holder.LoginUserContextHolder;
import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.note.biz.constant.MQConstants;
import com.quanxiaoha.xiaohashu.note.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteCollectionDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteInteractionMapper;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteLikeDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.TopicDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.enums.*;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.*;
import com.quanxiaoha.xiaohashu.note.biz.rpc.DistributedIdGeneratorRpcService;
import com.quanxiaoha.xiaohashu.note.biz.rpc.KeyValueRpcService;
import com.quanxiaoha.xiaohashu.note.biz.rpc.UserRpcService;
import com.quanxiaoha.xiaohashu.note.biz.rpc.CountRpcService;
import com.quanxiaoha.xiaohashu.note.biz.rpc.RelationRpcService;
import com.quanxiaoha.xiaohashu.count.api.dto.NoteCountDTO;
import java.util.function.Supplier;
import java.util.concurrent.CompletionException;
import com.quanxiaoha.xiaohashu.note.biz.service.NoteService;
import com.quanxiaoha.xiaohashu.user.dto.resp.FindUserByIdRspDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * @author: 犬小哈
 * @date: 2024/4/7 15:41
 * @version: v1.0.0
 * @description: 笔记业务
 **/
@Service
@Slf4j
public class NoteServiceImpl implements NoteService {

    @Resource
    private NoteDOMapper noteDOMapper;
    @Resource
    private TopicDOMapper topicDOMapper;
    @Resource
    private DistributedIdGeneratorRpcService distributedIdGeneratorRpcService;
    @Resource
    private KeyValueRpcService keyValueRpcService;
    @Resource
    private UserRpcService userRpcService;
    @Resource(name = "taskExecutor")
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;
    @Resource
    private RedisTemplate<String, String> redisTemplate;
    @Resource
    private RocketMQTemplate rocketMQTemplate;
    @Resource
    private NoteLikeDOMapper noteLikeDOMapper;
    @Resource
    private NoteCollectionDOMapper noteCollectionDOMapper;
    @Resource
    private NoteInteractionPublisher noteInteractionPublisher;
    @Resource
    private NoteCountEventService noteCountEventService;
    @Resource
    private NoteInteractionMapper noteInteractionMapper;
    @Resource
    private CountRpcService countRpcService;
    @Resource
    private RelationRpcService relationRpcService;

    @org.springframework.beans.factory.annotation.Value("${note.detail.rpc-timeout-millis:3000}")
    private long detailRpcTimeoutMillis = 3000;

    /**
     * 笔记详情本地缓存
     */
    private static final Cache<Long, String> LOCAL_CACHE = Caffeine.newBuilder()
            .initialCapacity(10000) // 设置初始容量为 10000 个条目
            .maximumSize(10000) // 设置缓存的最大容量为 10000 个条目
            .expireAfterWrite(1, TimeUnit.HOURS) // 设置缓存条目在写入后 1 小时过期
            .build();

    /**
     * 发布笔记。
     * 流程：按类型校验图片/视频 → 用分布式 ID 生成笔记 ID → 正文写入 KV 存储（Cassandra）
     * → 元数据写 t_note，并在同一个事务里登记 NOTE_PUBLISHED 事件（供计数服务加作品数）。
     * 正文和元数据分库存放：正文大、元数据小，查询模式也不同。
     *
     * @param publishNoteReqVO 标题、类型、图片/视频地址、正文、话题
     */
    @Override
    public Response<?> publishNote(PublishNoteReqVO publishNoteReqVO) {
        // 笔记类型
        Integer type = publishNoteReqVO.getType();

        // 获取对应类型的枚举
        NoteTypeEnum noteTypeEnum = NoteTypeEnum.valueOf(type);

        // 若非图文、视频，抛出业务业务异常
        if (Objects.isNull(noteTypeEnum)) {
            throw new BizException(ResponseCodeEnum.NOTE_TYPE_ERROR);
        }

        String imgUris = null;
        // 笔记内容是否为空，默认值为 true，即空
        Boolean isContentEmpty = true;
        String videoUri = null;
        switch (noteTypeEnum) {
            case IMAGE_TEXT: // 图文笔记
                List<String> imgUriList = publishNoteReqVO.getImgUris();
                // 校验图片是否为空
                Preconditions.checkArgument(CollUtil.isNotEmpty(imgUriList), "笔记图片不能为空");
                // 校验图片数量
                Preconditions.checkArgument(imgUriList.size() <= 8, "笔记图片不能多于 8 张");
                // 将图片链接拼接，以逗号分隔
                imgUris = StringUtils.join(imgUriList, ",");

                break;
            case VIDEO: // 视频笔记
                videoUri = publishNoteReqVO.getVideoUri();
                // 校验视频链接是否为空
                Preconditions.checkArgument(StringUtils.isNotBlank(videoUri), "笔记视频不能为空");
                break;
            default:
                break;
        }

        // RPC: 调用分布式 ID 生成服务（雪花算法），生成笔记 ID
        String snowflakeIdId = distributedIdGeneratorRpcService.getSnowflakeId();  // TODO: 为什么用雪花算法？
        // 笔记内容 UUID
        String contentUuid = null;  // TODO: 为什么不用笔记 id

        // 笔记内容
        String content = publishNoteReqVO.getContent();

        // 若用户填写了笔记内容
        if (StringUtils.isNotBlank(content)) {
            // 内容是否为空，置为 false，即不为空
            isContentEmpty = false;
            // 生成笔记内容 UUID
            contentUuid = UUID.randomUUID().toString();
            // RPC: 调用 KV 键值服务，存储短文本
            boolean isSavedSuccess = keyValueRpcService.saveNoteContent(contentUuid, content);

            // 若存储失败，抛出业务异常，提示用户发布笔记失败
            if (!isSavedSuccess) {
                throw new BizException(ResponseCodeEnum.NOTE_PUBLISH_FAIL);
            }
        }

        // 话题
        Long topicId = publishNoteReqVO.getTopicId();
        String topicName = null;
        if (Objects.nonNull(topicId)) {
            // 获取话题名称
            topicName = topicDOMapper.selectNameByPrimaryKey(topicId);
        }

        // 发布者用户 ID
        Long creatorId = LoginUserContextHolder.getUserId();

        // 构建笔记 DO 对象
        NoteDO noteDO = NoteDO.builder()
                .id(Long.valueOf(snowflakeIdId))
                .isContentEmpty(isContentEmpty)
                .creatorId(creatorId)
                .imgUris(imgUris)
                .title(publishNoteReqVO.getTitle())
                .topicId(publishNoteReqVO.getTopicId())
                .topicName(topicName)
                .type(type)
                .visible(NoteVisibleEnum.PUBLIC.getCode())
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .status(NoteStatusEnum.NORMAL.getCode())
                .isTop(Boolean.FALSE)
                .videoUri(videoUri)
                .contentUuid(contentUuid)
                .build();

        try {
            // 笔记入库存储
            noteCountEventService.publish(noteDO);
        } catch (Exception e) {
            log.error("==> 笔记存储失败", e);
            if (StringUtils.isNotBlank(contentUuid)) {
                // RPC: 笔记保存失败，则删除笔记内容
                keyValueRpcService.deleteNoteContent(contentUuid);
            }
            throw new BizException(ResponseCodeEnum.NOTE_PUBLISH_FAIL);
        }

        return Response.success();
    }

    /**
     * 查询笔记详情。
     * 读路径是三级缓存：Caffeine 本地 → Redis → MySQL；回源后用 CompletableFuture 并发拉
     * 作者、正文、计数、关注状态四个下游，把多次 RTT 压成一次。
     * 缓存里只放“公共详情”（不含计数和个人状态），否则会把 A 用户的“已点赞”污染给 B。
     * 笔记不存在时写一个短 TTL 的空值，防止缓存穿透。
     *
     * @param findNoteDetailReqVO 笔记 ID
     */
    @Override
    public Response<FindNoteDetailRspVO> findNoteDetail(FindNoteDetailReqVO findNoteDetailReqVO) {
        // 查询的笔记 ID
        Long noteId = findNoteDetailReqVO.getId();

        // 当前登录用户
        Long userId = LoginUserContextHolder.getUserId();

        // 先从本地缓存中查询
        String findNoteDetailRspVOStrLocalCache = LOCAL_CACHE.getIfPresent(noteId);
        if (StringUtils.isNotBlank(findNoteDetailRspVOStrLocalCache)) {
            FindNoteDetailRspVO findNoteDetailRspVO = JsonUtils.parseObject(findNoteDetailRspVOStrLocalCache, FindNoteDetailRspVO.class);
            log.info("==> 命中了本地缓存；{}", findNoteDetailRspVOStrLocalCache);
            // 可见性校验
            checkNoteVisibleFromVO(userId, findNoteDetailRspVO);
            return Response.success(fillDetailStatus(findNoteDetailRspVO, userId));
        }

        // 从 Redis 缓存中获取
        String noteDetailRedisKey = RedisKeyConstants.buildNoteDetailKey(noteId);
        String noteDetailJson = redisTemplate.opsForValue().get(noteDetailRedisKey);

        // 若 Redis 缓存中有该笔记的数据
        if (StringUtils.isNotBlank(noteDetailJson)) {
            FindNoteDetailRspVO findNoteDetailRspVO = JsonUtils.parseObject(noteDetailJson, FindNoteDetailRspVO.class);
            // 缓存原始公共详情，避免异步序列化时混入当前用户状态。
            LOCAL_CACHE.put(noteId, noteDetailJson);
            // 可见性校验
            checkNoteVisibleFromVO(userId, findNoteDetailRspVO);
            return Response.success(fillDetailStatus(findNoteDetailRspVO, userId));
        }

        // 若 Redis 缓存中获取不到，则走数据库查询
        NoteDO noteDO = noteDOMapper.selectByPrimaryKey(noteId);

        // 若该笔记不存在
        if (Objects.isNull(noteDO)) {
            threadPoolTaskExecutor.execute(() -> {
                // 防止缓存穿透，将空数据存入 Redis 缓存 (过期时间不宜设置过长)
                long expireSeconds = 60 + RandomUtil.randomInt(60);  // 保底1分钟 + 随机秒数
                redisTemplate.opsForValue().set(noteDetailRedisKey, "null", expireSeconds, TimeUnit.SECONDS);
            });
            throw new BizException(ResponseCodeEnum.NOTE_NOT_FOUND);
        }

        // 可见性校验
        Integer visible = noteDO.getVisible();
        checkNoteVisible(visible, userId, noteDO.getCreatorId());

        // 四个独立 RPC 同时提交，登录用户 ID 在请求线程中取得并显式传递。
        CompletableFuture<FindUserByIdRspDTO> userResultFuture = queryDetailAsync(
                () -> userRpcService.findById(noteDO.getCreatorId()), ResponseCodeEnum.NOTE_AUTHOR_LOAD_FAIL);
        CompletableFuture<String> contentResultFuture = CompletableFuture.completedFuture(null);
        if (Objects.equals(noteDO.getIsContentEmpty(), Boolean.FALSE)) {
            contentResultFuture = queryDetailAsync(
                    () -> keyValueRpcService.findNoteContent(noteDO.getContentUuid()), ResponseCodeEnum.NOTE_CONTENT_LOAD_FAIL);
        }
        CompletableFuture<NoteCountDTO> countResultFuture = queryDetailAsync(
                () -> countRpcService.findNote(noteId), null);
        CompletableFuture<Boolean> followingResultFuture = queryFollowingAsync(userId, noteDO.getCreatorId());

        FindUserByIdRspDTO author = awaitDetailResult(userResultFuture);
        String content = awaitDetailResult(contentResultFuture);
        List<String> imgUris = null;
        if (Objects.equals(noteDO.getType(), NoteTypeEnum.IMAGE_TEXT.getCode())
                && StringUtils.isNotBlank(noteDO.getImgUris())) {
            imgUris = List.of(noteDO.getImgUris().split(","));
        }
        FindNoteDetailRspVO detail = FindNoteDetailRspVO.builder()
                .id(noteId)
                .type(noteDO.getType())
                .title(noteDO.getTitle())
                .content(content)
                .imgUris(imgUris)
                .topicId(noteDO.getTopicId())
                .topicName(noteDO.getTopicName())
                .creatorId(noteDO.getCreatorId())
                .creatorName(author.getNickName())
                .avatar(author.getAvatar())
                .videoUri(noteDO.getVideoUri())
                .updateTime(noteDO.getUpdateTime())
                .visible(visible)
                .build();

        // 先序列化公共详情，再补充实时计数和个人状态，避免共享缓存污染。
        String publicDetailJson = JsonUtils.toJsonString(detail);
        threadPoolTaskExecutor.submit(() -> {
            long expireSeconds = 60 * 60 * 24 + RandomUtil.randomInt(60 * 60 * 24);
            redisTemplate.opsForValue().set(noteDetailRedisKey, publicDetailJson, expireSeconds, TimeUnit.SECONDS);
        });
        fillDetailStatus(detail, userId, countResultFuture, followingResultFuture);
        return Response.success(detail);
    }

    /**
     * 更新笔记。
     * 事务内更新元数据、更新 KV 里的正文，然后删 Redis 缓存并广播清本地缓存。
     * 注意这里的失效动作发生在事务提交之前，属于已知的一致性问题：
     * 并发读可能在提交前回源读到旧值并回填缓存，删除链路已经用 Outbox 修掉这个窗口，
     * 更新链路还没改。正文更新失败会抛异常让事务回滚。
     *
     * @param updateNoteReqVO 笔记 ID、标题、正文、图片/视频、话题
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Response<?> updateNote(UpdateNoteReqVO updateNoteReqVO) {
        // 笔记 ID
        Long noteId = updateNoteReqVO.getId();
        // 笔记类型
        Integer type = updateNoteReqVO.getType();

        // 获取对应类型的枚举
        NoteTypeEnum noteTypeEnum = NoteTypeEnum.valueOf(type);

        // 若非图文、视频，抛出业务业务异常
        if (Objects.isNull(noteTypeEnum)) {
            throw new BizException(ResponseCodeEnum.NOTE_TYPE_ERROR);
        }

        String imgUris = null;
        String videoUri = null;
        switch (noteTypeEnum) {
            case IMAGE_TEXT: // 图文笔记
                List<String> imgUriList = updateNoteReqVO.getImgUris();
                // 校验图片是否为空
                Preconditions.checkArgument(CollUtil.isNotEmpty(imgUriList), "笔记图片不能为空");
                // 校验图片数量
                Preconditions.checkArgument(imgUriList.size() <= 8, "笔记图片不能多于 8 张");

                imgUris = StringUtils.join(imgUriList, ",");
                break;
            case VIDEO: // 视频笔记
                videoUri = updateNoteReqVO.getVideoUri();
                // 校验视频链接是否为空
                Preconditions.checkArgument(StringUtils.isNotBlank(videoUri), "笔记视频不能为空");
                break;
            default:
                break;
        }


        // 当前登录用户 ID
        Long currUserId = LoginUserContextHolder.getUserId();
        NoteDO selectNoteDO = noteDOMapper.selectByPrimaryKey(noteId);

        // 笔记不存在
        if (Objects.isNull(selectNoteDO)) {
            throw new BizException(ResponseCodeEnum.NOTE_NOT_FOUND);
        }

        // 判断权限：非笔记发布者不允许更新笔记
        if (!Objects.equals(currUserId, selectNoteDO.getCreatorId())) {  // TODO: 这里的判断应该提前，放到前面
            throw new BizException(ResponseCodeEnum.NOTE_CANT_OPERATE);
        }

        // 话题
        Long topicId = updateNoteReqVO.getTopicId();
        String topicName = null;
        if (Objects.nonNull(topicId)) {
            topicName = topicDOMapper.selectNameByPrimaryKey(topicId);

            // 判断一下提交的话题, 是否是真实存在的
            if (StringUtils.isBlank(topicName)) throw new BizException(ResponseCodeEnum.TOPIC_NOT_FOUND);
        }


        // 更新笔记元数据表 t_note
        String content = updateNoteReqVO.getContent();
        NoteDO noteDO = NoteDO.builder()
                .id(noteId)
                .isContentEmpty(StringUtils.isBlank(content))
                .imgUris(imgUris)
                .title(updateNoteReqVO.getTitle())
                .topicId(updateNoteReqVO.getTopicId())
                .topicName(topicName)
                .type(type)
                .updateTime(LocalDateTime.now())
                .videoUri(videoUri)
                .build();

        noteDOMapper.updateByPrimaryKey(noteDO);

        // 删除 Redis 缓存
        String noteDetailRedisKey = RedisKeyConstants.buildNoteDetailKey(noteId);
        redisTemplate.delete(noteDetailRedisKey);

        // // 删除本地缓存
        // LOCAL_CACHE.invalidate(noteId);
        // 同步发送广播模式 MQ，将所有实例中的本地缓存都删除掉
        rocketMQTemplate.syncSend(MQConstants.TOPIC_DELETE_NOTE_LOCAL_CACHE, noteId);
        log.info("====> MQ：删除笔记本地缓存发送成功...");

        // 笔记内容更新
        // 查询此篇笔记内容对应的 UUID
        NoteDO noteDO1 = noteDOMapper.selectByPrimaryKey(noteId);
        String contentUuid = noteDO1.getContentUuid();

        // 笔记内容是否更新成功
        boolean isUpdateContentSuccess = false;
        if (StringUtils.isBlank(content)) {
            // 若笔记内容为空，则删除 K-V 存储
            isUpdateContentSuccess = keyValueRpcService.deleteNoteContent(contentUuid);
        } else {
            // 调用 K-V 更新短文本
            isUpdateContentSuccess = keyValueRpcService.saveNoteContent(contentUuid, content);
        }

        // 如果更新失败，抛出业务异常，回滚事务
        if (!isUpdateContentSuccess) {  // TODO: 目前的事务作用有限，存在一致性问题
            throw new BizException(ResponseCodeEnum.NOTE_UPDATE_FAIL);
        }

        return Response.success();
    }

    /**
     * 清掉本实例的笔记详情本地缓存。
     * 由广播模式的消费者调用：缓存失效消息要让每个实例都收到，只清自己进程内的 Caffeine。
     *
     * @param noteId 笔记 ID
     */
    public void deleteNoteLocalCache(Long noteId) {
        LOCAL_CACHE.invalidate(noteId);
    }

    /**
     * 删除笔记。
     * 用带状态条件的 UPDATE 防止并发重复删除；删除事实、计数事件（含删除时的互动快照）
     * 和缓存失效任务在同一个事务里提交，提交后才由投递器去清缓存。
     *
     * @param deleteNoteReqVO 笔记 ID
     * @return 成功即表示删除事实已提交，不代表缓存已清完
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Response<?> deleteNote(DeleteNoteReqVO deleteNoteReqVO) {
        // 笔记 ID
        Long noteId = deleteNoteReqVO.getId();

        NoteDO selectNoteDO = noteInteractionMapper.findNote(noteId);

        // 判断笔记是否存在
        if (Objects.isNull(selectNoteDO)) {
            throw new BizException(ResponseCodeEnum.NOTE_NOT_FOUND);
        }

        // 判断权限：非笔记发布者不允许删除笔记
        Long currUserId = LoginUserContextHolder.getUserId();
        if (!Objects.equals(currUserId, selectNoteDO.getCreatorId())) {
            throw new BizException(ResponseCodeEnum.NOTE_CANT_OPERATE);
        }

        if (Objects.equals(selectNoteDO.getStatus(), NoteStatusEnum.DELETED.getCode())) {
            return Response.success();
        }
        // 只有条件更新成功的请求才产生删除事件，避免并发删除重复扣减。
        int updatedRows = noteDOMapper.markDeletedIfStatus(noteId, currUserId,
                selectNoteDO.getStatus(), LocalDateTime.now());
        if (updatedRows == 0) {
            // 可能已被另一请求删除，或者状态被其他操作改变；均不再生成删除事件。
            return Response.success();
        }
        // 互动不锁笔记，删除快照允许遗漏并发提交的互动，接受作者汇总计数偏差。
        if (Objects.equals(selectNoteDO.getStatus(), NoteStatusEnum.NORMAL.getCode())) {
            noteCountEventService.append(selectNoteDO, "NOTE_DELETED",
                    noteInteractionMapper.countLikes(noteId), noteInteractionMapper.countCollections(noteId));
        }

        // 与删除事实同事务记录；投递器仅能看到已提交的任务，失败会自动重试。
        noteCountEventService.appendCacheInvalidation(noteId);

        return Response.success();
    }

    /**
     * 把笔记设为“仅自己可见”。
     * 只改可见性字段，不改正文；改完同样要清 Redis 详情缓存并广播清各实例的本地缓存，
     * 否则别的用户可能还在缓存里看到这篇笔记。
     *
     * @param updateNoteVisibleOnlyMeReqVO 笔记 ID
     */
    @Override
    public Response<?> visibleOnlyMe(UpdateNoteVisibleOnlyMeReqVO updateNoteVisibleOnlyMeReqVO) {
        // 笔记 ID
        Long noteId = updateNoteVisibleOnlyMeReqVO.getId();

        NoteDO selectNoteDO = noteDOMapper.selectByPrimaryKey(noteId);

        // 判断笔记是否存在
        if (Objects.isNull(selectNoteDO)) {
            throw new BizException(ResponseCodeEnum.NOTE_NOT_FOUND);
        }

        // 判断权限：非笔记发布者不允许修改笔记权限
        Long currUserId = LoginUserContextHolder.getUserId();
        if (!Objects.equals(currUserId, selectNoteDO.getCreatorId())) {
            throw new BizException(ResponseCodeEnum.NOTE_CANT_OPERATE);
        }

        // 构建更新 DO 实体类
        NoteDO noteDO = NoteDO.builder()
                .id(noteId)
                .visible(NoteVisibleEnum.PRIVATE.getCode()) // 可见性设置为仅对自己可见
                .updateTime(LocalDateTime.now())
                .build();

        // 执行更新 SQL
        int count = noteDOMapper.updateVisibleOnlyMe(noteDO);

        // 若影响的行数为 0，则表示该笔记无法修改为仅自己可见
        if (count == 0) {
            throw new BizException(ResponseCodeEnum.NOTE_CANT_VISIBLE_ONLY_ME);
        }

        // 删除 Redis 缓存
        String noteDetailRedisKey = RedisKeyConstants.buildNoteDetailKey(noteId);
        redisTemplate.delete(noteDetailRedisKey);

        // 同步发送广播模式 MQ，将所有实例中的本地缓存都删除掉
        rocketMQTemplate.syncSend(MQConstants.TOPIC_DELETE_NOTE_LOCAL_CACHE, noteId);
        log.info("====> MQ：删除笔记本地缓存发送成功...");

        return Response.success();
    }

    /**
     * 置顶 / 取消置顶笔记。
     * 只改置顶标记，不改正文；置顶状态会随详情一起返回，所以更新后同样要失效缓存。
     *
     * @param topNoteReqVO 笔记 ID + 是否置顶
     */
    @Override
    public Response<?> topNote(TopNoteReqVO topNoteReqVO) {
        // 笔记 ID
        Long noteId = topNoteReqVO.getId();
        // 是否置顶
        Boolean isTop = topNoteReqVO.getIsTop();

        // 当前登录用户 ID
        Long currUserId = LoginUserContextHolder.getUserId();

        // 构建置顶/取消置顶 DO 实体类
        NoteDO noteDO = NoteDO.builder()
                .id(noteId)
                .isTop(isTop)
                .updateTime(LocalDateTime.now())
                .creatorId(currUserId) // 只有笔记所有者，才能置顶/取消置顶笔记
                .build();

        int count = noteDOMapper.updateIsTop(noteDO);

        if (count == 0) {
            throw new BizException(ResponseCodeEnum.NOTE_CANT_OPERATE);
        }

        // 删除 Redis 缓存
        String noteDetailRedisKey = RedisKeyConstants.buildNoteDetailKey(noteId);
        redisTemplate.delete(noteDetailRedisKey);

        // 同步发送广播模式 MQ，将所有实例中的本地缓存都删除掉
        rocketMQTemplate.syncSend(MQConstants.TOPIC_DELETE_NOTE_LOCAL_CACHE, noteId);
        log.info("====> MQ：删除笔记本地缓存发送成功...");

        return Response.success();
    }

    /**
     * 点赞笔记。
     * 接口只发一条顺序消息（路由键 userId:noteId）就返回 ACCEPTED，不写库；
     * 真正的互动事实和计数由消费端异步完成，客户端用同一个 requestId 重试。
     *
     * @param likeNoteReqVO 笔记 ID + 本次操作的 requestId
     */
    @Override
    public Response<?> likeNote(LikeNoteReqVO likeNoteReqVO) {
        return noteInteractionPublisher.submit(likeNoteReqVO.getRequestId(), likeNoteReqVO.getId(), "LIKE", true);
    }

    /**
     * 取消点赞笔记。
     * 与点赞共用同一条链路和同一个顺序队列，保证“点赞→取消”按发送顺序被计算。
     *
     * @param unlikeNoteReqVO 笔记 ID + 本次操作的 requestId
     */
    @Override
    public Response<?> unlikeNote(UnlikeNoteReqVO unlikeNoteReqVO) {
        return noteInteractionPublisher.submit(unlikeNoteReqVO.getRequestId(), unlikeNoteReqVO.getId(), "LIKE", false);
    }

    /**
     * 收藏笔记。
     * 与点赞同一套异步模型：先受理、后算事实，收藏数由计数服务维护。
     *
     * @param collectNoteReqVO 笔记 ID + 本次操作的 requestId
     */
    @Override
    public Response<?> collectNote(CollectNoteReqVO collectNoteReqVO) {
        return noteInteractionPublisher.submit(collectNoteReqVO.getRequestId(), collectNoteReqVO.getId(), "COLLECT", true);
    }

    /**
     * 取消收藏笔记。
     * 与收藏共用队列，避免同一用户对同一篇笔记的收藏状态被乱序计算。
     *
     * @param unCollectNoteReqVO 笔记 ID + 本次操作的 requestId
     */
    @Override
    public Response<?> unCollectNote(UnCollectNoteReqVO unCollectNoteReqVO) {
        return noteInteractionPublisher.submit(unCollectNoteReqVO.getRequestId(), unCollectNoteReqVO.getId(), "COLLECT", false);
    }

    /**
     * 校验笔记的可见性
     * @param visible 是否可见
     * @param currUserId 当前用户 ID
     * @param creatorId 笔记创建者
     */
    private void checkNoteVisible(Integer visible, Long currUserId, Long creatorId) {
        if (Objects.equals(visible, NoteVisibleEnum.PRIVATE.getCode())
                && !Objects.equals(currUserId, creatorId)) { // 仅自己可见, 并且访问用户不是笔记创建者
            throw new BizException(ResponseCodeEnum.NOTE_PRIVATE);
        }
    }

    /**
     * 校验笔记的可见性（针对 VO 实体类）
     * @param userId
     * @param findNoteDetailRspVO
     */
    private void checkNoteVisibleFromVO(Long userId, FindNoteDetailRspVO findNoteDetailRspVO) {
        if (Objects.nonNull(findNoteDetailRspVO)) {
            Integer visible = findNoteDetailRspVO.getVisible();
            checkNoteVisible(visible, userId, findNoteDetailRspVO.getCreatorId());
        }
    }


    /** 缓存命中时复用正文和作者资料，仅并发补充计数与当前用户的关注状态。 */
    private FindNoteDetailRspVO fillDetailStatus(FindNoteDetailRspVO detail, Long userId) {
        if (detail == null) {
            throw new BizException(ResponseCodeEnum.NOTE_NOT_FOUND);
        }
        CompletableFuture<NoteCountDTO> countFuture = queryDetailAsync(
                () -> countRpcService.findNote(detail.getId()), null);
        CompletableFuture<Boolean> followingFuture = queryFollowingAsync(userId, detail.getCreatorId());
        fillDetailStatus(detail, userId, countFuture, followingFuture);
        return detail;
    }

    /** 在请求线程统一组装动态字段；不可用的计数和关注状态不伪装成零或未关注。 */
    private void fillDetailStatus(FindNoteDetailRspVO detail, Long userId,
            CompletableFuture<NoteCountDTO> countFuture, CompletableFuture<Boolean> followingFuture) {
        NoteCountDTO counts = awaitDetailResult(countFuture);
        detail.setCountsAvailable(counts != null);
        detail.setLikeTotal(counts == null ? null : counts.getLikeTotal());
        detail.setCollectTotal(counts == null ? null : counts.getCollectTotal());
        Boolean following = awaitDetailResult(followingFuture);
        detail.setFollowing(following);
        detail.setFollowingAvailable(following != null);
        detail.setLiked(userId != null && noteLikeDOMapper.selectCountByUserIdAndNoteId(userId, detail.getId()) > 0);
        detail.setCollected(userId != null && noteCollectionDOMapper.selectCountByUserIdAndNoteId(userId, detail.getId()) > 0);
    }

    /** 未登录和查看自己的笔记无需调用关系服务，其他情况显式传递访问者和作者 ID。 */
    private CompletableFuture<Boolean> queryFollowingAsync(Long userId, Long authorId) {
        if (userId == null || Objects.equals(userId, authorId)) {
            return CompletableFuture.completedFuture(false);
        }
        return queryDetailAsync(() -> relationRpcService.isFollowing(userId, authorId), null);
    }

    /** 提交有时限的详情查询；指定错误码的核心任务失败时报错，其余任务降级为未知。 */
    private <T> CompletableFuture<T> queryDetailAsync(Supplier<T> query, ResponseCodeEnum requiredError) {
        CompletableFuture<T> queryFuture;
        try {
            queryFuture = CompletableFuture.supplyAsync(query, threadPoolTaskExecutor);
        } catch (RuntimeException exception) {
            queryFuture = CompletableFuture.failedFuture(exception);
        }
        return queryFuture.orTimeout(detailRpcTimeoutMillis, TimeUnit.MILLISECONDS).handle((result, exception) -> {
            if (exception != null || result == null) {
                log.warn("笔记详情下游查询失败，核心任务错误码：{}", requiredError, exception);
                if (requiredError != null) {
                    throw new BizException(requiredError);
                }
                return null;
            }
            return result;
        });
    }

    /** 解包异步业务异常，让全局异常处理器返回明确的正文或作者加载错误。 */
    private <T> T awaitDetailResult(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof BizException) {
                throw (BizException) exception.getCause();
            }
            throw exception;
        }
    }

    /**
     * 分页读取“我点赞过的笔记”。
     * 只读事务用 REPEATABLE_READ：总数和当前页取自同一个快照，避免翻页时总数变化导致错位；
     * 只返回仍然可见的笔记（已删除/私密的会被过滤）。
     *
     * @param request 页码与每页条数（最多 100）
     */
    @Override
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResponse<FindInteractionNoteListRspVO> findLikedNotes(FindInteractionNoteListReqVO request) {
        Long userId = validateInteractionListRequest(request);
        long totalCount = noteLikeDOMapper.countVisibleNotes(userId);
        long offset = PageResponse.getOffset(request.getPageNo(), request.getPageSize());
        List<FindInteractionNoteListRspVO> notes = java.util.Collections.emptyList();
        if (offset < totalCount) {
            notes = noteLikeDOMapper.selectVisibleNotesPage(userId, offset, request.getPageSize());
        }
        return PageResponse.success(notes, request.getPageNo(), totalCount, request.getPageSize());
    }

    /**
     * 分页读取“我收藏过的笔记”。
     * 与点赞列表同构：同一快照内取总数和当前页，只返回仍然可见的笔记。
     *
     * @param request 页码与每页条数（最多 100）
     */
    @Override
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResponse<FindInteractionNoteListRspVO> findCollectedNotes(FindInteractionNoteListReqVO request) {
        Long userId = validateInteractionListRequest(request);
        long totalCount = noteCollectionDOMapper.countVisibleNotes(userId);
        long offset = PageResponse.getOffset(request.getPageNo(), request.getPageSize());
        List<FindInteractionNoteListRspVO> notes = java.util.Collections.emptyList();
        if (offset < totalCount) {
            notes = noteCollectionDOMapper.selectVisibleNotesPage(userId, offset, request.getPageSize());
        }
        return PageResponse.success(notes, request.getPageNo(), totalCount, request.getPageSize());
    }

    /**
     * 校验互动列表的分页参数和登录身份。
     * 用户 ID 只从登录态取，不接受客户端传参，避免查到别人的列表。
     *
     * @return 当前登录用户 ID
     */
    private Long validateInteractionListRequest(FindInteractionNoteListReqVO request) {
        if (request == null || request.getPageNo() == null || request.getPageNo() < 1
                || request.getPageSize() == null || request.getPageSize() < 1 || request.getPageSize() > 100) {
            throw new BizException(ResponseCodeEnum.PARAM_NOT_VALID);
        }
        Long userId = LoginUserContextHolder.getUserId();
        if (userId == null || userId <= 0) {
            throw new BizException(ResponseCodeEnum.NOTE_CANT_OPERATE);
        }
        return userId;
    }
}
