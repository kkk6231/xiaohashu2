package com.quanxiaoha.xiaohashu.user.relation.biz.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 列表缓存快照，包含完整性标记和固定过期时间。 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RelationCacheSnapshotDTO {
    /** 是否覆盖全部有效关系；false 时不能把列表外用户判定为无关系。 */
    private boolean complete;
    /** 整份 ZSet 的成员数，不是本页条数；截断缓存中也不是用户粉丝总数。 */
    private long count;
    /** Redis 固定过期时间，单位为毫秒；增量更新沿用此值。 */
    private long expires;
    /** 本次读取范围内的成员，按关注时间、用户 ID 倒序排列。 */
    private java.util.List<RelationCacheEntryDTO> entries;
}
