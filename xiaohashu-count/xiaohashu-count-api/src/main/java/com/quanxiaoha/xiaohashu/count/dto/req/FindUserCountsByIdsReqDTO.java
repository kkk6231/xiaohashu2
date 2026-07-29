package com.quanxiaoha.xiaohashu.count.dto.req;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量查询用户计数请求参数。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FindUserCountsByIdsReqDTO {

    /**
     * 需要查询计数的用户 ID 集合。
     */
    private List<Long> userIds;
}
