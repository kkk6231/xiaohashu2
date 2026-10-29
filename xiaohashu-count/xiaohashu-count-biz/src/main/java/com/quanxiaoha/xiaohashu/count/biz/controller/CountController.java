package com.quanxiaoha.xiaohashu.count.biz.controller;

import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.count.api.RelationCountDTO;
import com.quanxiaoha.xiaohashu.count.api.dto.*;
import com.quanxiaoha.xiaohashu.count.biz.service.CountService;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@Validated
public class CountController {
    @Resource
    private CountService countService;

    @Value("${count.internal-token}")
    private String internalToken;

    /** 内部消费链路提交计数增量；调用成功后，消息消费者才能确认消息。 */
    @PostMapping("/internal/count/changes/batch")
    public Response<Void> apply(@RequestBody CountChangeBatchDTO batch,
                               @RequestHeader("X-Count-Token") String token) {
        if (internalToken.isBlank() || !internalToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        countService.apply(batch.getChanges());
        return Response.success();
    }

    /** 保留关系查询接口，只返回关注数和粉丝数。 */
    @GetMapping("/count/relation")
    public Response<RelationCountDTO> findRelation(@RequestParam("userId") @Positive long userId) {
        UserCountDTO counts = countService.findUsers(List.of(userId)).get(0);
        RelationCountDTO result = new RelationCountDTO();
        result.setFollowingTotal(counts.getFollowingTotal());
        result.setFansTotal(counts.getFansTotal());
        return Response.success(result);
    }

    /** 查询用户的关注、粉丝、获赞、获收藏和作品数。 */
    @GetMapping("/count/user")
    public Response<UserCountDTO> findUser(@RequestParam long userId) {
        return Response.success(countService.findUsers(List.of(userId)).get(0));
    }

    /** 查询笔记的点赞数和收藏数。 */
    @GetMapping("/count/note")
    public Response<NoteCountDTO> findNote(@RequestParam long noteId) {
        return Response.success(countService.findNotes(List.of(noteId)).get(0));
    }

    /** 批量查询用户计数，返回顺序与传入 ID 顺序一致。 */
    @PostMapping("/count/user/batch")
    public Response<List<UserCountDTO>> findUsers(@RequestBody Map<String, List<Long>> body) {
        return Response.success(countService.findUsers(body.get("userIds")));
    }

    /** 批量查询笔记计数，供列表展示使用。 */
    @PostMapping("/count/note/batch")
    public Response<List<NoteCountDTO>> findNotes(@RequestBody Map<String, List<Long>> body) {
        return Response.success(countService.findNotes(body.get("noteIds")));
    }
}
