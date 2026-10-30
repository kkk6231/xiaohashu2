package com.quanxiaoha.xiaohashu.count.api.dto;

import lombok.Data;

@Data
public class UserCountDTO {
    private long userId;
    private Long followingTotal;
    private Long fansTotal;
    private Long likesReceivedTotal;
    private Long collectsReceivedTotal;
    private Long worksTotal;
}
