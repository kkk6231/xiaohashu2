package com.quanxiaoha.xiaohashu.user.relation.biz.controller;

import com.quanxiaoha.framework.biz.operationlog.aspect.ApiOperationLog;
import com.quanxiaoha.framework.common.response.PageResponse;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.user.relation.biz.model.vo.*;
import com.quanxiaoha.xiaohashu.user.relation.biz.service.RelationService;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.Positive;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author: 犬小哈
 * @date: 2024/4/4 13:22
 * @version: v1.0.0
 * @description: 用户关系
 */
@RestController
@RequestMapping("/relation")
@Slf4j
@Validated
public class RelationController {

    @Resource
    private RelationService relationService;

    /**
     * 关注用户。
     * 参数为被关注者 ID，发起人取自登录态；成功表示关注表与事件已同事务提交。
     */
    @PostMapping("/follow")
    @ApiOperationLog(description = "关注用户")
    public Response<?> follow(@Validated @RequestBody FollowUserReqVO followUserReqVO) {
        return relationService.follow(followUserReqVO);
    }

    /**
     * 取消关注。
     * 已经是未关注状态时直接返回，不产生新事件。
     */
    @PostMapping("/unfollow")
    @ApiOperationLog(description = "取关用户")
    public Response<?> unfollow(@Validated @RequestBody UnfollowUserReqVO unfollowUserReqVO) {
        return relationService.unfollow(unfollowUserReqVO);
    }

    /**
     * 查询某个用户的关注列表（分页）。
     * 关注有业务上限，列表缓存保存的是完整列表，能直接按页返回。
     */
    @PostMapping("/following/list")
    @ApiOperationLog(description = "查询用户关注列表")
    public PageResponse<FindFollowingUserRspVO> findFollowingList(
            @Validated @RequestBody FindFollowingListReqVO findFollowingListReqVO) {
        return relationService.findFollowingList(findFollowingListReqVO);
    }

    /**
     * 查询某个用户的粉丝列表（分页）。
     * 粉丝可能非常多，缓存只保留最近一批；总数以计数服务为准，可能与列表有短暂差异。
     */
    @PostMapping("/fans/list")
    @ApiOperationLog(description = "查询用户粉丝列表")
    public PageResponse<FindFansUserRspVO> findFansList(
            @Validated @RequestBody FindFansListReqVO findFansListReqVO) {
        return relationService.findFansList(findFansListReqVO);
    }

    /**
     * 查询用户的关注数与粉丝数。
     * 读的是计数服务的在线计数，不扫关系表。
     */
    @GetMapping("/count")
    @ApiOperationLog(description = "查询用户关系计数")
    public Response<?> findCount(@RequestParam("userId") @Positive long userId) {
        return relationService.findCount(userId);
    }

    /**
     * 查询“fromUserId 是否关注了 toUserId”。
     * 只查这一对关系，不加载列表，也不初始化缓存。
     */
    @GetMapping("/status")
    @ApiOperationLog(description = "查询关注状态")
    public Response<?> findFollowingStatus(
            @RequestParam("fromUserId") @Positive long fromUserId,
            @RequestParam("toUserId") @Positive long toUserId) {
        return relationService.findFollowingStatus(fromUserId, toUserId);
    }

    /**
     * 批量查询关注状态：这批目标用户里，我关注了哪些。
     * 最多 100 个 ID，结果按入参顺序返回。
     */
    @PostMapping("/following/status/batch")
    @ApiOperationLog(description = "批量查询关注状态")
    public Response<?> findFollowingStatuses(@Validated @RequestBody RelationBatchReqVO request) {
        return relationService.findFollowingStatuses(request);
    }

    /**
     * 批量查询粉丝状态：这批候选人里，谁关注了我。
     * 走粉丝投影表，避免在关注主表上做反向查询。
     */
    @PostMapping("/followers/status/batch")
    @ApiOperationLog(description = "批量查询粉丝状态")
    public Response<?> findFansStatuses(@Validated @RequestBody RelationBatchReqVO request) {
        return relationService.findFansStatuses(request);
    }
}
