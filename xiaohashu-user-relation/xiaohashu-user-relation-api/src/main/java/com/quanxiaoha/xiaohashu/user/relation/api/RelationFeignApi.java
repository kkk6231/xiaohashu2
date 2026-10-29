package com.quanxiaoha.xiaohashu.user.relation.api;

import com.quanxiaoha.framework.common.response.Response;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.Map;

/** 笔记详情使用的关系查询客户端，复用关系服务已有接口。 */
@FeignClient(name = "xiaohashu-user-relation", contextId = "noteRelationClient")
public interface RelationFeignApi {
    /** 查询访问者是否关注笔记作者。 */
    @GetMapping("/relation/status")
    Response<Map<String, Boolean>> findFollowingStatus(@RequestParam("fromUserId") long userId,
            @RequestParam("toUserId") long authorId);
}
