package com.quanxiaoha.xiaohashu.gateway.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler exceptionHandler;

    /**
     * 创建不依赖 Spring 容器的网关异常处理器。
     */
    @BeforeEach
    void setUp() {
        exceptionHandler = new GlobalExceptionHandler();
        ReflectionTestUtils.setField(exceptionHandler, "objectMapper", new ObjectMapper());
    }

    /**
     * 未登录异常应返回 HTTP 401。
     */
    @Test
    void shouldReturnUnauthorizedWhenUserIsNotLoggedIn() {
        MockServerWebExchange exchange = createExchange();
        NotLoginException exception = new NotLoginException("请先登录", null, null);

        exceptionHandler.handle(exchange, exception).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    /**
     * 已登录但没有接口权限时应返回 HTTP 403。
     */
    @Test
    void shouldReturnForbiddenWhenUserHasNoPermission() {
        MockServerWebExchange exchange = createExchange();
        NotPermissionException exception = new NotPermissionException("无权访问该接口");

        exceptionHandler.handle(exchange, exception).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    /**
     * 创建用于测试异常响应的模拟请求上下文。
     */
    private MockServerWebExchange createExchange() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/test").build();
        return MockServerWebExchange.from(request);
    }
}
