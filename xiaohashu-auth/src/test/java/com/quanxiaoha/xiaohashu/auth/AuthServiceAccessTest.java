package com.quanxiaoha.xiaohashu.auth;

import com.quanxiaoha.framework.common.enums.StatusEnum;
import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.xiaohashu.auth.enums.LoginTypeEnum;
import com.quanxiaoha.xiaohashu.auth.enums.ResponseCodeEnum;
import com.quanxiaoha.xiaohashu.auth.model.vo.user.UserLoginReqVO;
import com.quanxiaoha.xiaohashu.auth.rpc.UserRpcService;
import com.quanxiaoha.xiaohashu.auth.service.impl.AuthServiceImpl;
import com.quanxiaoha.xiaohashu.user.dto.resp.FindUserByPhoneRspDTO;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthServiceAccessTest {

    /**
     * 禁用账号即使密码正确也不应获得 Token。
     */
    @Test
    void shouldRejectDisabledUserBeforeCreatingToken() {
        UserRpcService userRpcService = mock(UserRpcService.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        FindUserByPhoneRspDTO loginUser = FindUserByPhoneRspDTO.builder()
                .id(10L)
                .password("encoded-password")
                .status(StatusEnum.DISABLED.getValue())
                .build();
        when(userRpcService.findUserByPhone("13800138000")).thenReturn(loginUser);
        when(passwordEncoder.matches("raw-password", "encoded-password")).thenReturn(true);

        AuthServiceImpl authService = new AuthServiceImpl();
        ReflectionTestUtils.setField(authService, "userRpcService", userRpcService);
        ReflectionTestUtils.setField(authService, "passwordEncoder", passwordEncoder);
        UserLoginReqVO request = UserLoginReqVO.builder()
                .phone("13800138000")
                .password("raw-password")
                .type(LoginTypeEnum.PASSWORD.getValue())
                .build();

        BizException exception = assertThrows(BizException.class,
                () -> authService.loginAndRegister(request));

        assertEquals(ResponseCodeEnum.USER_DISABLED.getErrorCode(), exception.getErrorCode());
    }
}
