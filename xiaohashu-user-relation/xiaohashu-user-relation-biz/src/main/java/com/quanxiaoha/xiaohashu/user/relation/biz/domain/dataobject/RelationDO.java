package com.quanxiaoha.xiaohashu.user.relation.biz.domain.dataobject;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RelationDO {
    private Long id;
    private Long fromUserId;
    private Long toUserId;
    private Integer type;
    private Long version;
    private LocalDateTime updateTime;
}
