package com.quanxiaoha.xiaohashu.user.relation.biz;

import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.count.api.RelationCountDTO;
import com.quanxiaoha.xiaohashu.count.api.RelationCountFeignApi;
import com.quanxiaoha.xiaohashu.user.relation.biz.config.RelationProperties;
import com.quanxiaoha.xiaohashu.user.relation.biz.rpc.RelationCountRpcService;
import com.quanxiaoha.xiaohashu.user.relation.biz.service.impl.RelationDatabaseRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RelationReadSupportTest {
    @Test
    void sharedLimiterRejectsWhenItsPermitIsConsumed() {
        RelationProperties properties = new RelationProperties();
        properties.setDatabaseQueryPermitsPerSecond(0.000001);
        RelationDatabaseRateLimiter limiter = new RelationDatabaseRateLimiter();
        ReflectionTestUtils.setField(limiter, "relationProperties", properties);
        limiter.init();
        limiter.acquire();
        assertThrows(BizException.class, limiter::acquire);
    }

    @Test
    void counterFailureIsNotReturnedAsZero() {
        RelationCountFeignApi api = mock(RelationCountFeignApi.class);
        RelationCountRpcService rpc = new RelationCountRpcService();
        ReflectionTestUtils.setField(rpc, "relationCountFeignApi", api);
        RelationCountDTO count = new RelationCountDTO();
        count.setFansTotal(123L);
        when(api.find(1)).thenReturn(Response.success(count), Response.fail(), Response.success());
        assertEquals(123, rpc.findFansTotal(1));
        assertThrows(BizException.class, () -> rpc.findFansTotal(1));
        assertThrows(BizException.class, () -> rpc.findFansTotal(1));
    }
}
