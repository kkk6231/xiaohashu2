package com.quanxiaoha.xiaohashu.count.api.dto;

import lombok.Data;

@Data
public class UserCountDTO {
    private long userId;
    private long followingTotal;
    private long fansTotal;
    private long likesReceivedTotal;
    private long collectsReceivedTotal;
    private long worksTotal;
}
