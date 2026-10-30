package com.quanxiaoha.xiaohashu.note.biz.service.impl;

import com.quanxiaoha.framework.biz.context.holder.LoginUserContextHolder;
import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.count.api.event.NoteInteractionEvent;
import com.quanxiaoha.xiaohashu.note.biz.constant.RedisKeyConstants;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteLikeDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteCollectionDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteLikeDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteCollectionDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.enums.NoteStatusEnum;
import com.quanxiaoha.xiaohashu.note.biz.enums.NoteVisibleEnum;
import com.quanxiaoha.xiaohashu.note.biz.enums.ResponseCodeEnum;
import jakarta.annotation.Resource;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.protocol.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 点赞收藏入口：缓存辅助判断后发送一条顺序互动事件。 */
@Service
@Slf4j
public class NoteInteractionPublisher {
    private static final int CACHE_LIMIT = 100;
    private static final long CACHE_SECONDS = 86400;
    private static final long PENDING_SECONDS = 60;
    @Resource
    private RocketMQTemplate rocketMQTemplate;
    @Resource
    private StringRedisTemplate redisTemplate;
    @Resource
    private NoteDOMapper noteMapper;
    @Resource
    private NoteLikeDOMapper likeMapper;
    @Resource
    private NoteCollectionDOMapper collectionMapper;

    /** 服务端生成事件身份；发送结果未知时返回 UNKNOWN，不宣称已受理。 */
    public Response<?> submit(long noteId, String action, boolean targetState) {
        Long userId = LoginUserContextHolder.getUserId();
        if (userId == null || userId <= 0 || noteId <= 0 || !("LIKE".equals(action) || "COLLECT".equals(action))) {
            throw new BizException(ResponseCodeEnum.PARAM_NOT_VALID);
        }
        NoteDO note = noteMapper.selectByPrimaryKey(noteId);
        if (note == null || note.getCreatorId() == null || note.getCreatorId() <= 0
                || !Objects.equals(note.getStatus(), NoteStatusEnum.NORMAL.getCode())) {
            throw new BizException(ResponseCodeEnum.NOTE_NOT_FOUND);
        }
        if (!Objects.equals(userId, note.getCreatorId()) && !Objects.equals(note.getVisible(), NoteVisibleEnum.PUBLIC.getCode())) {
            throw new BizException(ResponseCodeEnum.NOTE_PRIVATE);
        }
        boolean like = "LIKE".equals(action);
        String listKey = like ? RedisKeyConstants.buildUserNoteLikeZSetKey(userId)
                : RedisKeyConstants.buildUserNoteCollectZSetKey(userId);
        String bloomKey = like ? RedisKeyConstants.buildBloomUserNoteLikeListKey(userId)
                : RedisKeyConstants.buildBloomUserNoteCollectListKey(userId);
        String pendingKey = listKey + ":pending:" + noteId;
        String previousPending = redisTemplate.opsForValue().get(pendingKey);
        boolean active = isActive(userId, noteId, like, listKey, bloomKey, previousPending);
        if (active == targetState) {
            if (like) {
                throw new BizException(targetState ? ResponseCodeEnum.NOTE_ALREADY_LIKED : ResponseCodeEnum.NOTE_NOT_LIKED);
            }
            throw new BizException(targetState ? ResponseCodeEnum.NOTE_ALREADY_COLLECTED : ResponseCodeEnum.NOTE_NOT_COLLECTED);
        }
        String eventId = UUID.randomUUID().toString();
        String cacheVersionKey = listKey + ":version";
        try {
            // 先登记版本，初始化缓存中途失败时也能按版本安全失效。
            redisTemplate.opsForValue().set(cacheVersionKey, eventId, Duration.ofSeconds(CACHE_SECONDS));
            if (targetState) {
                initializeRecentList(userId, like, listKey);
                executeCacheScript("interaction-zset-add.lua", listKey, cacheVersionKey, pendingKey,
                        String.valueOf(noteId), String.valueOf(System.currentTimeMillis()),
                        String.valueOf(CACHE_LIMIT), String.valueOf(CACHE_SECONDS), eventId,
                        String.valueOf(PENDING_SECONDS));
                executeScript(like ? "bloom_add_note_like_and_expire.lua" : "bloom_add_note_collect_and_expire.lua",
                        bloomKey, String.valueOf(noteId), String.valueOf(CACHE_SECONDS));
            } else {
                executeCacheScript("interaction-zset-remove.lua", listKey, cacheVersionKey, pendingKey,
                        String.valueOf(noteId), eventId, String.valueOf(CACHE_SECONDS), String.valueOf(PENDING_SECONDS));
            }
        } catch (RuntimeException exception) {
            invalidateRejectedCache(listKey, cacheVersionKey, pendingKey, eventId, previousPending);
            throw exception;
        }
        NoteInteractionEvent event = new NoteInteractionEvent();
        event.setEventId(eventId);
        event.setUserId(userId);
        event.setNoteId(noteId);
        event.setAuthorId(note.getCreatorId());
        event.setActionType(action);
        event.setTargetState(targetState);
        event.validate();
        try {
            var result = rocketMQTemplate.syncSendOrderly(NoteInteractionEvent.TOPIC,
                    MessageBuilder.withPayload(JsonUtils.toJsonString(event)).build(), String.valueOf(userId));
            if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                log.warn("互动消息发送结果未知，eventId: {}", eventId);
                return Response.success(Map.of("status", "UNKNOWN"));
            }
        } catch (RuntimeException exception) {
            if (isDefinitelyRejected(exception)) {
                invalidateRejectedCache(listKey, cacheVersionKey, pendingKey, eventId, previousPending);
                throw exception;
            }
            log.warn("互动消息发送结果未知，eventId: {}", eventId, exception);
            return Response.success(Map.of("status", "UNKNOWN"));
        }
        return Response.success();
    }

    /** 只将 Broker 明确拒绝且未接受的响应认定为发送失败；超时和其他状态均为未知。 */
    private boolean isDefinitelyRejected(Throwable exception) {
        Throwable cause = exception;
        while (cause != null) {
            // 顺序发送在找不到 Topic 路由时尚未向 Broker 发送；其他客户端异常按结果未知处理。
            if (cause instanceof MQClientException clientException
                    && clientException.getErrorMessage() != null
                    && clientException.getErrorMessage().startsWith("No route info for this topic")) {
                return true;
            }
            if (cause instanceof MQBrokerException brokerException) {
                int code = brokerException.getResponseCode();
                return code == ResponseCode.NO_PERMISSION || code == ResponseCode.TOPIC_NOT_EXIST
                        || code == ResponseCode.MESSAGE_ILLEGAL;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /** 若没有后续修改则失效缓存；并发后续操作不会被回滚。 */
    private void invalidateRejectedCache(String listKey, String versionKey, String pendingKey, String eventId, String previousPending) {
        try {
            Long invalidated = executeCacheScript("interaction-cache-invalidate.lua", listKey, versionKey, pendingKey, eventId,
                    previousPending == null ? "" : previousPending, String.valueOf(PENDING_SECONDS));
            if (!Objects.equals(invalidated, 1L)) {
                log.warn("互动发送被拒绝，但缓存已有后续修改，无法安全失效，eventId: {}", eventId);
            }
        } catch (RuntimeException exception) {
            log.error("互动发送被拒绝，缓存失效失败，eventId: {}", eventId, exception);
        }
    }

    /** 对同槽位的列表和版本标记原子执行缓存脚本。 */
    private Long executeCacheScript(String file, String listKey, String versionKey, String pendingKey, String... arguments) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/" + file));
        script.setResultType(Long.class);
        return redisTemplate.execute(script, List.of(listKey, versionKey, pendingKey), (Object[]) arguments);
    }

    /** 最近列表命中可快速判断；其余情况通过事实表确认。 */
    private boolean isActive(long userId, long noteId, boolean like, String listKey, String bloomKey, String pending) {
        if (pending != null && pending.endsWith(":1")) {
            return true;
        }
        if (pending != null && pending.endsWith(":0")) {
            return false;
        }
        if (redisTemplate.opsForZSet().score(listKey, String.valueOf(noteId)) != null) {
            return true;
        }
        initializeBloom(userId, like, bloomKey);
        String script = like ? "bloom_note_like_check.lua" : "bloom_note_collect_check.lua";
        Long bloomResult = executeScript(script, bloomKey, String.valueOf(noteId));
        // 缓存未命中可快速排除；可能命中或尚未初始化时由数据库确认。
        if (Objects.equals(bloomResult, 0L) && Boolean.TRUE.equals(redisTemplate.hasKey(bloomKey + ":ready"))) {
            return false;
        }
        return like ? likeMapper.selectCountByUserIdAndNoteId(userId, noteId) > 0
                : collectionMapper.selectCountByUserIdAndNoteId(userId, noteId) > 0;
    }

    /** 首次使用时从事实表加载过滤器；只追加，不覆盖并发写入的成员。 */
    private void initializeBloom(long userId, boolean like, String bloomKey) {
        if (Boolean.TRUE.equals(redisTemplate.hasKey(bloomKey))) {
            return;
        }
        java.util.List<String> arguments = new java.util.ArrayList<>();
        if (like) {
            for (NoteLikeDO row : likeMapper.selectByUserId(userId)) arguments.add(String.valueOf(row.getNoteId()));
        } else {
            for (NoteCollectionDO row : collectionMapper.selectByUserId(userId)) arguments.add(String.valueOf(row.getNoteId()));
        }
        if (arguments.isEmpty()) {
            return;
        }
        arguments.add(String.valueOf(CACHE_SECONDS));
        String script = like ? "bloom_batch_add_note_like_and_expire.lua" : "bloom_batch_add_note_collect_and_expire.lua";
        executeScript(script, bloomKey, arguments.toArray(new String[0]));
        // 只有历史记录全部写完后才标记过滤器可用于排除不存在的记录。
        redisTemplate.opsForValue().set(bloomKey + ":ready", "1", Duration.ofSeconds(CACHE_SECONDS - 5));
    }

    /** 缓存不存在时从数据库恢复最近互动记录。 */
    private void initializeRecentList(long userId, boolean like, String listKey) {
        if (Boolean.TRUE.equals(redisTemplate.hasKey(listKey))) {
            return;
        }
        if (like) {
            List<NoteLikeDO> rows = likeMapper.selectLikedByUserIdAndLimit(userId, CACHE_LIMIT);
            for (NoteLikeDO row : rows) {
                redisTemplate.opsForZSet().add(listKey, String.valueOf(row.getNoteId()), row.getCreateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            }
        } else {
            List<NoteCollectionDO> rows = collectionMapper.selectCollectedByUserIdAndLimit(userId, CACHE_LIMIT);
            for (NoteCollectionDO row : rows) {
                redisTemplate.opsForZSet().add(listKey, String.valueOf(row.getNoteId()), row.getCreateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            }
        }
    }

    /** 执行 Bloom 和列表缓存 Lua 脚本。 */
    private Long executeScript(String file, String key, String... arguments) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/" + file));
        script.setResultType(Long.class);
        return redisTemplate.execute(script, List.of(key), (Object[]) arguments);
    }
}
