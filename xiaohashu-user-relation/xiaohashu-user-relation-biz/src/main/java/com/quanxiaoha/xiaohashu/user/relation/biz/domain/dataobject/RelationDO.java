package com.quanxiaoha.xiaohashu.user.relation.biz.domain.dataobject;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelationDO {
    private Long id;
    private Long fromUserId;
    private Long toUserId;
    private Integer type;
    private Long version;
    private LocalDateTime updateTime;
}
