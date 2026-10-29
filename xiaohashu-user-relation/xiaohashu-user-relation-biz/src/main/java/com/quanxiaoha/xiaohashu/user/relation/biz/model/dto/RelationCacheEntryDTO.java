package com.quanxiaoha.xiaohashu.user.relation.biz.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 列表缓存成员及业务时间。 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RelationCacheEntryDTO {
    private long id;
    private long millis;
}
