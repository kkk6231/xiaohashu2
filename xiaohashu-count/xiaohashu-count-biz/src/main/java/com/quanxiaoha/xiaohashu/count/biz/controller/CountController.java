package com.quanxiaoha.xiaohashu.count.biz.controller;

import com.quanxiaoha.framework.biz.operationlog.aspect.ApiOperationLog;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.count.biz.service.UserCountService;
import com.quanxiaoha.xiaohashu.count.dto.req.FindUserCountsByIdsReqDTO;
import com.quanxiaoha.xiaohashu.count.dto.resp.FindUserCountByIdRspDTO;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 计数服务 HTTP/RPC 入口。
 *
 * <p>当前主要给其他微服务通过 Feign 调用，查询用户维度的聚合计数。</p>
 */
@RestController
@RequestMapping("/count")
public class CountController {

    @Resource
    private UserCountService userCountService;

    /**
     * 批量查询用户计数。
     *
     * <p>查询过程中如果 Redis 缓存不存在，会在服务层从 DB 回源并初始化完整 Hash。</p>
     */
    @PostMapping("/user/counts")
    @ApiOperationLog(description = "批量查询用户计数")
    public Response<List<FindUserCountByIdRspDTO>> findUserCountsByIds(@RequestBody FindUserCountsByIdsReqDTO findUserCountsByIdsReqDTO) {
        return userCountService.findUserCountsByIds(findUserCountsByIdsReqDTO);
    }
}
