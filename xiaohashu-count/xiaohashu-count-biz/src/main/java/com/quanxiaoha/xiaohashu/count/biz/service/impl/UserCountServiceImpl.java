package com.quanxiaoha.xiaohashu.count.biz.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.count.biz.service.UserCountCacheService;
import com.quanxiaoha.xiaohashu.count.biz.service.UserCountService;
import com.quanxiaoha.xiaohashu.count.dto.req.FindUserCountsByIdsReqDTO;
import com.quanxiaoha.xiaohashu.count.dto.resp.FindUserCountByIdRspDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 用户计数查询服务实现。
 *
 * <p>这一层只负责查询参数处理和结果包装，真正的 Redis 命中、DB 回源、
 * 缓存初始化逻辑统一交给 {@link UserCountCacheService}。</p>
 */
@Service
public class UserCountServiceImpl implements UserCountService {

    @Resource
    private UserCountCacheService userCountCacheService;

    @Override
    public Response<List<FindUserCountByIdRspDTO>> findUserCountsByIds(FindUserCountsByIdsReqDTO findUserCountsByIdsReqDTO) {
        List<Long> userIds = findUserCountsByIdsReqDTO.getUserIds();
        if (CollUtil.isEmpty(userIds)) {
            return Response.success(Collections.emptyList());
        }

        // 去掉空 ID 和重复 ID，避免无意义的 Redis/DB 查询。
        List<FindUserCountByIdRspDTO> result = userIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .map(userCountCacheService::getOrInitUserCount)
                .toList();

        return Response.success(result);
    }
}
