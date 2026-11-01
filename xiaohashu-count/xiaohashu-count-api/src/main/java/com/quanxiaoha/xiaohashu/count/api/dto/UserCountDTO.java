package com.quanxiaoha.xiaohashu.count.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserCountDTO {
    private long userId;
    private Long followingTotal;
    private Long fansTotal;
    private Long likesReceivedTotal;
    private Long collectsReceivedTotal;
    private Long worksTotal;
}
