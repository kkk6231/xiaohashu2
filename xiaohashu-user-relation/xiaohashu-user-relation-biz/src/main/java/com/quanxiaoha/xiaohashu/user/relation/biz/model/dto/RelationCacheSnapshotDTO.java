package com.quanxiaoha.xiaohashu.user.relation.biz.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 列表缓存快照：整份 ZSet 的成员数 + 本次读取范围内的成员。 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RelationCacheSnapshotDTO {
    /** 整份 ZSet 的成员数（ZCARD），不是本页条数；截断缓存中也不是用户粉丝总数。 */
    private long count;
    /** 本次读取范围内的成员，按关注时间、用户 ID 倒序排列。 */
    private java.util.List<RelationCacheEntryDTO> entries;
}
