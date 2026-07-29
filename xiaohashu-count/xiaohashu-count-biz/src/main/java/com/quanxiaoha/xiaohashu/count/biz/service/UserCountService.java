package com.quanxiaoha.xiaohashu.count.biz.service;

import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.count.dto.req.FindUserCountsByIdsReqDTO;
import com.quanxiaoha.xiaohashu.count.dto.resp.FindUserCountByIdRspDTO;

import java.util.List;

/**
 * 用户维度计数查询服务。
 */
public interface UserCountService {

    /**
     * 批量查询用户维度计数。
     *
     * <p>该方法面向业务查询场景：优先读 Redis；Redis 未命中时回源 DB，
     * 并初始化完整的用户计数 Hash。</p>
     *
     * @param findUserCountsByIdsReqDTO 查询条件
     * @return 用户计数列表
     */
    Response<List<FindUserCountByIdRspDTO>> findUserCountsByIds(FindUserCountsByIdsReqDTO findUserCountsByIdsReqDTO);
}
