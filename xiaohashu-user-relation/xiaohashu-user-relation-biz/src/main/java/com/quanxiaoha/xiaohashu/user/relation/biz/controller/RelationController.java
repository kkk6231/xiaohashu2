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

    @PostMapping("/follow")
    @ApiOperationLog(description = "关注用户")
    public Response<?> follow(@Validated @RequestBody FollowUserReqVO followUserReqVO) {
        return relationService.follow(followUserReqVO);
    }

    @PostMapping("/unfollow")
    @ApiOperationLog(description = "取关用户")
    public Response<?> unfollow(@Validated @RequestBody UnfollowUserReqVO unfollowUserReqVO) {
        return relationService.unfollow(unfollowUserReqVO);
    }

    @PostMapping("/following/list")
    @ApiOperationLog(description = "查询用户关注列表")
    public PageResponse<FindFollowingUserRspVO> findFollowingList(
            @Validated @RequestBody FindFollowingListReqVO findFollowingListReqVO) {
        return relationService.findFollowingList(findFollowingListReqVO);
    }

    @PostMapping("/fans/list")
    @ApiOperationLog(description = "查询用户粉丝列表")
    public PageResponse<FindFansUserRspVO> findFansList(
            @Validated @RequestBody FindFansListReqVO findFansListReqVO) {
        return relationService.findFansList(findFansListReqVO);
    }

    @GetMapping("/count")
    @ApiOperationLog(description = "查询用户关系计数")
    public Response<?> findCount(@RequestParam("userId") @Positive long userId) {
        return relationService.findCount(userId);
    }

    @GetMapping("/status")
    @ApiOperationLog(description = "查询关注状态")
    public Response<?> findFollowingStatus(
            @RequestParam("fromUserId") @Positive long fromUserId,
            @RequestParam("toUserId") @Positive long toUserId) {
        return relationService.findFollowingStatus(fromUserId, toUserId);
    }

    @PostMapping("/following/status/batch")
    @ApiOperationLog(description = "批量查询关注状态")
    public Response<?> findFollowingStatuses(@Validated @RequestBody RelationBatchReqVO request) {
        return relationService.findFollowingStatuses(request);
    }

    @PostMapping("/followers/status/batch")
    @ApiOperationLog(description = "批量查询粉丝状态")
    public Response<?> findFansStatuses(@Validated @RequestBody RelationBatchReqVO request) {
        return relationService.findFansStatuses(request);
    }
}
