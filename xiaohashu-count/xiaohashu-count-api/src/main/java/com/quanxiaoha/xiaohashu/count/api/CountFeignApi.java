package com.quanxiaoha.xiaohashu.count.api;

import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.count.constant.ApiConstants;
import com.quanxiaoha.xiaohashu.count.dto.req.FindUserCountsByIdsReqDTO;
import com.quanxiaoha.xiaohashu.count.dto.resp.FindUserCountByIdRspDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 计数服务对外暴露的 Feign API。
 *
 * <p>用户关系服务、笔记服务等业务服务通过该接口批量查询用户维度计数，
 * 计数服务内部负责 Redis 未命中时的 DB 回源和缓存初始化。</p>
 */
@FeignClient(name = ApiConstants.SERVICE_NAME)
public interface CountFeignApi {

    String PREFIX = "/count";

    /**
     * 批量查询用户维度计数。
     *
     * @param findUserCountsByIdsReqDTO 用户 ID 集合
     * @return 用户计数列表，包含粉丝数、关注数、笔记数、点赞数、收藏数
     */
    @PostMapping(value = PREFIX + "/user/counts")
    Response<List<FindUserCountByIdRspDTO>> findUserCountsByIds(@RequestBody FindUserCountsByIdsReqDTO findUserCountsByIdsReqDTO);
}
