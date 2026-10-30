package com.quanxiaoha.xiaohashu.user.relation.biz;

import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.xiaohashu.user.relation.biz.config.RelationProperties;
import com.quanxiaoha.xiaohashu.user.relation.biz.domain.mapper.RelationDOMapper;
import com.quanxiaoha.xiaohashu.user.relation.biz.service.impl.RelationListCache;
import com.quanxiaoha.xiaohashu.user.relation.biz.service.impl.RelationLocks;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 锁竞争和状态查询的回归测试，不依赖外部 Redis/MySQL。 */
class RelationReliabilityTest {

    @Test
    void followingStatusDoesNotInitializeListOrTouchRedis() {
        RelationDOMapper mapper = mock(RelationDOMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RelationListCache cache = new RelationListCache();
        ReflectionTestUtils.setField(cache, "relationDOMapper", mapper);
        ReflectionTestUtils.setField(cache, "stringRedisTemplate", redis);
        when(mapper.followingStatuses(1, List.of(2L, 3L))).thenReturn(List.of(2L));

        var statuses = cache.followingStatuses(1, List.of(2L, 3L));

        assertTrue(statuses.get(2L));
        assertFalse(statuses.get(3L));
        verify(mapper).followingStatuses(1, List.of(2L, 3L));
        verifyNoMoreInteractions(mapper);
        verifyNoInteractions(redis);
    }

    @Test
    void followerStatusDoesNotAccessRedisOrAcquireListLock() {
        RelationDOMapper mapper = mock(RelationDOMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RelationLocks locks = mock(RelationLocks.class);
        RelationListCache cache = new RelationListCache();
        ReflectionTestUtils.setField(cache, "relationDOMapper", mapper);
        ReflectionTestUtils.setField(cache, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(cache, "relationLocks", locks);
        when(mapper.followerStatuses(1, List.of(2L, 3L))).thenReturn(List.of(2L));

        var statuses = cache.followerStatuses(1, List.of(2L, 3L));

        assertTrue(statuses.get(2L));
        assertFalse(statuses.get(3L));
        verify(mapper).followerStatuses(1, List.of(2L, 3L));
        verifyNoMoreInteractions(mapper);
        verifyNoInteractions(redis, locks);
    }

    @Test
    void emptyStatusRequestDoesNotQueryDatabase() {
        RelationDOMapper mapper = mock(RelationDOMapper.class);
        RelationListCache cache = new RelationListCache();
        ReflectionTestUtils.setField(cache, "relationDOMapper", mapper);
        assertTrue(cache.followingStatuses(1, List.of()).isEmpty());
        assertTrue(cache.followerStatuses(1, List.of()).isEmpty());
        verifyNoInteractions(mapper);
    }

    @Test
    void contentionCanRecoverWithinWaitBudget() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false, true);
        RelationLocks locks = locks(redis, 1000);
        try (var handle = locks.acquire("following", 1)) {
            assertNotNull(handle.token());
        }
        verify(values, times(2)).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void timeoutStillFailsInsteadOfPretendingSuccess() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        RelationLocks locks = locks(redis, 30);
        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> assertThrows(BizException.class, () -> locks.acquire("following", 1)));
    }

    @Test
    void interruptedRequestDoesNotAcquireLock() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RelationLocks locks = locks(redis, 200);
        Thread.currentThread().interrupt();
        try {
            assertThrows(BizException.class, () -> locks.acquire("following", 1));
            assertTrue(Thread.currentThread().isInterrupted());
            verifyNoInteractions(redis);
        } finally {
            Thread.interrupted();
        }
    }

    private RelationLocks locks(StringRedisTemplate redis, long waitMillis) {
        RelationProperties properties = new RelationProperties();
        properties.setLockWaitMillis(waitMillis);
        RelationLocks locks = new RelationLocks();
        ReflectionTestUtils.setField(locks, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(locks, "relationProperties", properties);
        return locks;
    }
}
