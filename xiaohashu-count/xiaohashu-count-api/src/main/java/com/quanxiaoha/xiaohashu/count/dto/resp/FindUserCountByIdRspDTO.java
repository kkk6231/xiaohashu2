package com.quanxiaoha.xiaohashu.count.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户维度计数响应 DTO。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FindUserCountByIdRspDTO {

    /**
     * 用户 ID。
     */
    private Long userId;

    /**
     * 粉丝总数。
     */
    private Long fansTotal;

    /**
     * 关注总数。
     */
    private Long followingTotal;

    /**
     * 发布笔记总数。
     */
    private Long noteTotal;

    /**
     * 获赞总数。
     */
    private Long likeTotal;

    /**
     * 被收藏总数。
     */
    private Long collectTotal;
}
