package com.quanxiaoha.xiaohashu.auth.model.vo.user;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 管理员调整用户访问状态的请求参数。
 */
@Data
public class ManageUserAccessReqVO {

    @NotNull(message = "用户 ID 不能为空")
    @Positive(message = "用户 ID 必须为正数")
    private Long userId;
}
