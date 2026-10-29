package com.quanxiaoha.xiaohashu.count.api;

import com.quanxiaoha.framework.common.response.Response;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "xiaohashu-count", contextId = "relationCounts")
public interface RelationCountFeignApi {
    @GetMapping("/count/relation")
    Response<RelationCountDTO> find(@RequestParam("userId") long userId);
}
