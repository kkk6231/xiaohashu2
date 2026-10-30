package com.quanxiaoha.xiaohashu.user.relation.biz.service.impl;

import com.quanxiaoha.framework.biz.context.holder.LoginUserContextHolder;
import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.framework.common.response.*;
import com.quanxiaoha.xiaohashu.user.dto.resp.FindUserByIdRspDTO;
import com.quanxiaoha.xiaohashu.user.relation.biz.config.RelationProperties;
import com.quanxiaoha.xiaohashu.user.relation.biz.domain.mapper.RelationDOMapper;
import com.quanxiaoha.xiaohashu.user.relation.biz.enums.ResponseCodeEnum;
import com.quanxiaoha.xiaohashu.user.relation.biz.model.dto.RelationCacheEntryDTO;
import com.quanxiaoha.xiaohashu.user.relation.biz.model.dto.RelationCacheSnapshotDTO;
import com.quanxiaoha.xiaohashu.user.relation.biz.domain.dataobject.RelationDO;
import com.quanxiaoha.xiaohashu.user.relation.biz.model.vo.*;
import com.quanxiaoha.xiaohashu.user.relation.biz.rpc.RelationCountRpcService;
import com.quanxiaoha.xiaohashu.user.relation.biz.rpc.UserRpcService;
import com.quanxiaoha.xiaohashu.user.relation.biz.service.RelationService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * @description: 用户关系业务，负责参数校验、关系操作及列表查询。
 */
@Service
public class RelationServiceImpl implements RelationService {

    @Resource
    private RelationCommandService relationCommandService;
    @Resource
    private RelationListCache relationListCache;
    @Resource
    private RelationDOMapper relationDOMapper;
    @Resource
    private UserRpcService userRpcService;
    @Resource
    private RelationCountRpcService relationCountRpcService;
    @Resource
    private RelationProperties relationProperties;

    /**
     * 关注用户。操作成功表示关系主表和本地消息均已提交。
     */
    @Override
    public Response<?> follow(FollowUserReqVO followUserReqVO) {
        // 获取当前登录用户与待关注用户
        Long userId = getLoginUserId();
        Long followUserId = followUserReqVO.getFollowUserId();

        // 校验：不能关注自己，且目标用户必须存在
        if (Objects.equals(userId, followUserId)) {
            throw new BizException(ResponseCodeEnum.CANT_FOLLOW_YOUR_SELF);
        }
        FindUserByIdRspDTO findUserByIdRspDTO = userRpcService.findById(followUserId);
        if (Objects.isNull(findUserByIdRspDTO)) {
            throw new BizException(ResponseCodeEnum.FOLLOW_USER_NOT_EXISTED);
        }

        // 同事务更新关系并记录消息，缓存由消费者异步维护
        boolean following = relationCommandService.change(userId, followUserId, true);
        return Response.success(Collections.singletonMap("following", following));
    }

    /**
     * 取消关注。重复取关直接返回未关注状态，不产生新事件。
     */
    @Override
    public Response<?> unfollow(UnfollowUserReqVO unfollowUserReqVO) {
        Long userId = getLoginUserId();
        Long unfollowUserId = unfollowUserReqVO.getUnfollowUserId();

        if (Objects.equals(userId, unfollowUserId)) {
            throw new BizException(ResponseCodeEnum.CANT_UNFOLLOW_YOUR_SELF);
        }

        boolean following = relationCommandService.change(userId, unfollowUserId, false);
        return Response.success(Collections.singletonMap("following", following));
    }

    /**
     * 从登录上下文取当前用户 ID，取不到就抛“未登录”业务异常。
     * 关系写操作都以它作为发起人，不能信任客户端传入的用户 ID。
     */
    private Long getLoginUserId() {
        Long userId = LoginUserContextHolder.getUserId();
        if (Objects.isNull(userId)) {
            throw new BizException(ResponseCodeEnum.LOGIN_REQUIRED);
        }
        return userId;
    }

    /**
     * 查询关注列表。关注数有业务上限，因此缓存保存完整列表。
     */
    @Override
    public PageResponse<FindFollowingUserRspVO> findFollowingList(FindFollowingListReqVO findFollowingListReqVO) {
        Long userId = findFollowingListReqVO.getUserId();
        Integer pageNo = findFollowingListReqVO.getPageNo();
        Integer pageSize = findFollowingListReqVO.getPageSize();
        long offset = PageResponse.getOffset(pageNo, pageSize);

        // 缓存未初始化时，从主库加载完整关注列表
        RelationCacheSnapshotDTO cacheSnapshot = relationListCache.load("following", userId, offset, offset + pageSize - 1);
        List<Long> followingUserIds = extractUserIds(cacheSnapshot);
        List<FindUserByIdRspDTO> userInfos = findUserInfosInOrder(followingUserIds);

        // 按关注时间顺序组装返回数据
        List<FindFollowingUserRspVO> followingUsers = new ArrayList<>(userInfos.size());
        for (FindUserByIdRspDTO userInfo : userInfos) {
            FindFollowingUserRspVO followingUser = FindFollowingUserRspVO.builder()
                    .userId(userInfo.getId())
                    .avatar(userInfo.getAvatar())
                    .nickname(userInfo.getNickName())
                    .introduction(userInfo.getIntroduction())
                    .build();
            followingUsers.add(followingUser);
        }
        return PageResponse.success(followingUsers, pageNo, cacheSnapshot.getCount(), pageSize);
    }

    /**
     * 查询粉丝列表。粉丝数没有上限，缓存只保留最近 fansCacheSize 位；
     * 超出缓存范围的页直接拒绝——缓存是唯一数据源，回源数据库会在缓存失效时打穿 DB。
     */
    @Override
    public PageResponse<FindFansUserRspVO> findFansList(FindFansListReqVO findFansListReqVO) {
        Long userId = findFansListReqVO.getUserId();
        Integer pageNo = findFansListReqVO.getPageNo();
        Integer pageSize = findFansListReqVO.getPageSize();
        long offset = PageResponse.getOffset(pageNo, pageSize);

        // 只提供最近 N 位粉丝，超出范围的页直接拒绝，不再回源数据库
        if (offset + pageSize > relationProperties.getFansCacheSize()) {
            throw new BizException(ResponseCodeEnum.FANS_PAGE_LIMIT_EXCEEDED);
        }

        RelationCacheSnapshotDTO cacheSnapshot =
                relationListCache.load("followers", userId, offset, offset + pageSize - 1);
        List<Long> fansUserIds = extractUserIds(cacheSnapshot);
        // 截断缓存的长度不是总数，粉丝总数一律取计数服务
        long fansTotal = relationCountRpcService.findFansTotal(userId);

        List<FindUserByIdRspDTO> userInfos = findUserInfosInOrder(fansUserIds);
        List<FindFansUserRspVO> fansUsers = new ArrayList<>(userInfos.size());
        for (FindUserByIdRspDTO userInfo : userInfos) {
            FindFansUserRspVO fansUser = FindFansUserRspVO.builder()
                    .userId(userInfo.getId())
                    .avatar(userInfo.getAvatar())
                    .nickname(userInfo.getNickName())
                    .build();
            fansUsers.add(fansUser);
        }
        return PageResponse.success(fansUsers, pageNo, fansTotal, pageSize);
    }

    /**
     * 把缓存条目转换成用户 ID 列表，保持缓存里已经排好的顺序。
     */
    private List<Long> extractUserIds(RelationCacheSnapshotDTO cacheSnapshot) {
        List<Long> userIds = new ArrayList<>(cacheSnapshot.getEntries().size());
        for (RelationCacheEntryDTO cacheEntry : cacheSnapshot.getEntries()) {
            userIds.add(cacheEntry.getId());
        }
        return userIds;
    }

    /**
     * 批量查询用户信息，并恢复关系列表顺序；RPC 返回顺序不作为排序依据。
     */
    private List<FindUserByIdRspDTO> findUserInfosInOrder(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<FindUserByIdRspDTO> userInfos = userRpcService.findByIds(userIds);
        if (Objects.isNull(userInfos)) {
            throw new BizException(ResponseCodeEnum.SYSTEM_ERROR);
        }

        Map<Long, FindUserByIdRspDTO> userInfoMap = new HashMap<>();
        for (FindUserByIdRspDTO userInfo : userInfos) {
            userInfoMap.put(userInfo.getId(), userInfo);
        }
        List<FindUserByIdRspDTO> orderedUserInfos = new ArrayList<>(userIds.size());
        for (Long userId : userIds) {
            FindUserByIdRspDTO userInfo = userInfoMap.get(userId);
            if (Objects.nonNull(userInfo)) {
                orderedUserInfos.add(userInfo);
            }
        }
        return orderedUserInfos;
    }

    /**
     * 查询用户的关注数与粉丝数，转调计数服务。
     */
    @Override
    public Response<?> findCount(long userId) {
        return relationCountRpcService.findCount(userId);
    }

    /**
     * 查询“我是否关注了某个人”。
     * 只查这一对关系，不加载整份关注列表。
     */
    @Override
    public Response<?> findFollowingStatus(long fromUserId, long toUserId) {
        List<Long> targetUserIds = Collections.singletonList(toUserId);
        Map<Long, Boolean> followingStatuses = relationListCache.followingStatuses(fromUserId, targetUserIds);
        return Response.success(Collections.singletonMap("following", followingStatuses.get(toUserId)));
    }

    /**
     * 批量查询关注状态：这批目标里，我关注了谁。
     * 结果按入参顺序返回，缺失的按“未关注”处理。
     */
    @Override
    public Response<?> findFollowingStatuses(RelationBatchReqVO relationBatchReqVO) {
        Map<Long, Boolean> followingStatuses = relationListCache.followingStatuses(
                relationBatchReqVO.getUserId(), relationBatchReqVO.getTargetUserIds());
        return Response.success(followingStatuses);
    }

    /**
     * 批量查询粉丝状态：这批候选人里，谁关注了我。
     * 走粉丝投影表，避免在关注主表上反向查询。
     */
    @Override
    public Response<?> findFansStatuses(RelationBatchReqVO relationBatchReqVO) {
        Map<Long, Boolean> fansStatuses = relationListCache.followerStatuses(
                relationBatchReqVO.getUserId(), relationBatchReqVO.getTargetUserIds());
        return Response.success(fansStatuses);
    }
}
