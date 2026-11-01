package com.quanxiaoha.xiaohashu.count.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelationCountDTO {
    private Long followingTotal;
    private Long fansTotal;
}
