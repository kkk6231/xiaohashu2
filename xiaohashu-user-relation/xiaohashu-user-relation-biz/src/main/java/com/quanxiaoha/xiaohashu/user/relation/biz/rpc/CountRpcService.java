package com.quanxiaoha.xiaohashu.user.relation.biz.rpc;

import cn.hutool.core.collection.CollUtil;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.count.api.CountFeignApi;
import com.quanxiaoha.xiaohashu.count.dto.req.FindUserCountsByIdsReqDTO;
import com.quanxiaoha.xiaohashu.count.dto.resp.FindUserCountByIdRspDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 计数服务 RPC 封装。
 *
 * <p>用户关系服务不直接关心计数服务的 Redis/DB 细节，只通过该类拿到展示用计数。
 * 这里也统一处理 RPC 失败降级，避免列表主流程被展示增强数据拖垮。</p>
 */
@Component
@Slf4j
public class CountRpcService {

    @Resource
    private CountFeignApi countFeignApi;

    /**
     * 批量查询用户计数。
     *
     * <p>粉丝列表的主数据来自关系服务，计数只是展示增强字段；如果计数服务异常，
     * 当前方法返回空列表，上层会把计数降级为 0。</p>
     *
     * @param userIds 用户 ID 集合
     * @return 计数服务返回的用户计数列表，失败时返回空列表
     */
    public List<FindUserCountByIdRspDTO> findUserCountsByIds(List<Long> userIds) {
        if (CollUtil.isEmpty(userIds)) {
            return Collections.emptyList();
        }

        FindUserCountsByIdsReqDTO reqDTO = FindUserCountsByIdsReqDTO.builder()
                .userIds(userIds)
                .build();

        Response<List<FindUserCountByIdRspDTO>> response;
        try {
            response = countFeignApi.findUserCountsByIds(reqDTO);
        } catch (Exception ex) {
            log.warn("==> 批量查询用户计数 RPC 异常, userIds: {}", userIds, ex);
            return Collections.emptyList();
        }

        if (Objects.isNull(response) || !response.isSuccess() || CollUtil.isEmpty(response.getData())) {
            log.warn("==> 批量查询用户计数失败或为空, userIds: {}, response: {}", userIds, response);
            return Collections.emptyList();
        }

        return response.getData();
    }
}
