package com.quanxiaoha.xiaohashu.user.relation.biz;

import com.quanxiaoha.xiaohashu.user.dto.resp.FindUserByIdRspDTO;
import com.quanxiaoha.xiaohashu.user.relation.biz.config.RelationProperties;
import com.quanxiaoha.xiaohashu.user.relation.biz.domain.dataobject.RelationDO;
import com.quanxiaoha.xiaohashu.user.relation.biz.domain.mapper.RelationDOMapper;
import com.quanxiaoha.xiaohashu.user.relation.biz.model.dto.RelationCacheEntryDTO;
import com.quanxiaoha.xiaohashu.user.relation.biz.model.dto.RelationCacheSnapshotDTO;
import com.quanxiaoha.xiaohashu.user.relation.biz.model.vo.*;
import com.quanxiaoha.xiaohashu.user.relation.biz.rpc.UserRpcService;
import com.quanxiaoha.xiaohashu.user.relation.biz.rpc.RelationCountRpcService;
import com.quanxiaoha.xiaohashu.user.relation.biz.service.impl.RelationDatabaseRateLimiter;
import com.quanxiaoha.xiaohashu.user.relation.biz.service.impl.RelationListCache;
import com.quanxiaoha.xiaohashu.user.relation.biz.service.impl.RelationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RelationListQueryTest {
    private RelationServiceImpl relationService;
    private RelationListCache relationListCache;
    private RelationDOMapper relationDOMapper;
    private UserRpcService userRpcService;
    private RelationCountRpcService relationCountRpcService;
    private RelationDatabaseRateLimiter databaseRateLimiter;

    @BeforeEach
    void setup() {
        relationService = new RelationServiceImpl();
        relationListCache = mock(RelationListCache.class);
        relationDOMapper = mock(RelationDOMapper.class);
        userRpcService = mock(UserRpcService.class);
        relationCountRpcService = mock(RelationCountRpcService.class);
        databaseRateLimiter = mock(RelationDatabaseRateLimiter.class);
        RelationProperties relationProperties = new RelationProperties();
        relationProperties.setFansCacheSize(3);
        ReflectionTestUtils.setField(relationService, "relationListCache", relationListCache);
        ReflectionTestUtils.setField(relationService, "relationDOMapper", relationDOMapper);
        ReflectionTestUtils.setField(relationService, "userRpcService", userRpcService);
        ReflectionTestUtils.setField(relationService, "relationProperties", relationProperties);
        ReflectionTestUtils.setField(relationService, "relationCountRpcService", relationCountRpcService);
        ReflectionTestUtils.setField(relationService, "relationDatabaseRateLimiter", databaseRateLimiter);
    }

    @Test
    void followingListPreservesRelationOrderWhenRpcReturnsDifferentOrder() {
        RelationCacheSnapshotDTO cacheSnapshot = new RelationCacheSnapshotDTO(true, 2, 1000,
                List.of(new RelationCacheEntryDTO(20, 200), new RelationCacheEntryDTO(10, 100)));
        when(relationListCache.load("following", 1, 0, 1)).thenReturn(cacheSnapshot);
        when(userRpcService.findByIds(List.of(20L, 10L))).thenReturn(List.of(user(10), user(20)));
        FindFollowingListReqVO request = FindFollowingListReqVO.builder().userId(1L).pageNo(1).pageSize(2).build();
        var response = relationService.findFollowingList(request);
        assertEquals(List.of(20L, 10L), response.getData().stream().map(FindFollowingUserRspVO::getUserId).toList());
        assertEquals(2, response.getTotalCount());
        verifyNoInteractions(relationDOMapper);
    }

    @Test
    void completeEmptyFansCacheDoesNotQueryDatabaseOrRpc() {
        when(relationListCache.load("followers", 1, 0, 1))
                .thenReturn(new RelationCacheSnapshotDTO(true, 0, 1000, List.of()));
        var response = relationService.findFansList(fansRequest(1, 2));
        assertTrue(response.getData().isEmpty());
        assertEquals(0, response.getTotalCount());
        verifyNoInteractions(relationDOMapper, userRpcService, relationCountRpcService, databaseRateLimiter);
    }

    @Test
    void incompleteCacheWithShortPageFallsBackToWholeDatabasePage() {
        when(relationListCache.load("followers", 1, 0, 1)).thenReturn(new RelationCacheSnapshotDTO(
                false, 1, 1000, List.of(new RelationCacheEntryDTO(30, 300))));
        when(relationCountRpcService.findFansTotal(1)).thenReturn(5L);
        when(relationDOMapper.followerPage(1, 0, 2)).thenReturn(List.of(follower(30), follower(20)));
        when(userRpcService.findByIds(List.of(30L, 20L))).thenReturn(List.of(user(30), user(20)));
        var response = relationService.findFansList(fansRequest(1, 2));
        assertEquals(5, response.getTotalCount());
        assertEquals(2, response.getData().size());
        verify(relationDOMapper).followerPage(1, 0, 2);
        verify(databaseRateLimiter).acquire();
        verifyNoMoreInteractions(relationDOMapper);
    }

    @Test
    void pageCrossingCacheBoundaryDoesNotMergeCacheAndDatabaseResults() {
        when(relationCountRpcService.findFansTotal(1)).thenReturn(4L);
        when(relationDOMapper.followerPage(1, 2, 2)).thenReturn(List.of(follower(20), follower(10)));
        when(userRpcService.findByIds(List.of(20L, 10L))).thenReturn(List.of(user(20), user(10)));
        var response = relationService.findFansList(fansRequest(2, 2));
        assertEquals(4, response.getTotalCount());
        assertEquals(2, response.getData().size());
        verifyNoInteractions(relationListCache);
        var order = inOrder(databaseRateLimiter, relationDOMapper);
        order.verify(databaseRateLimiter).acquire();
        order.verify(relationDOMapper).followerPage(1, 2, 2);
    }

    @Test
    void truncatedCacheHitUsesCounterWithoutDatabaseCountOrPermit() {
        when(relationListCache.load("followers", 1, 0, 1)).thenReturn(new RelationCacheSnapshotDTO(
                false, 3, 1000, List.of(new RelationCacheEntryDTO(30, 300), new RelationCacheEntryDTO(20, 200))));
        when(relationCountRpcService.findFansTotal(1)).thenReturn(1000000L);
        when(userRpcService.findByIds(List.of(30L, 20L))).thenReturn(List.of(user(30), user(20)));
        var response = relationService.findFansList(fansRequest(1, 2));
        assertEquals(1000000L, response.getTotalCount());
        assertEquals(2, response.getData().size());
        verifyNoInteractions(relationDOMapper, databaseRateLimiter);
    }

    @Test
    void deepPageRejectedBeforeDatabaseQuery() {
        when(relationCountRpcService.findFansTotal(1)).thenReturn(10L);
        doThrow(new com.quanxiaoha.framework.common.exception.BizException(
                com.quanxiaoha.xiaohashu.user.relation.biz.enums.ResponseCodeEnum.RELATION_BUSY))
                .when(databaseRateLimiter).acquire();
        assertThrows(com.quanxiaoha.framework.common.exception.BizException.class,
                () -> relationService.findFansList(fansRequest(3, 2)));
        verifyNoInteractions(relationDOMapper, relationListCache, userRpcService);
    }

    private FindFansListReqVO fansRequest(int pageNo, int pageSize) {
        return FindFansListReqVO.builder().userId(1L).pageNo(pageNo).pageSize(pageSize).build();
    }

    private FindUserByIdRspDTO user(long userId) {
        FindUserByIdRspDTO userInfo = new FindUserByIdRspDTO();
        userInfo.setId(userId);
        return userInfo;
    }

    private RelationDO follower(long userId) {
        RelationDO relationDO = new RelationDO();
        relationDO.setFromUserId(userId);
        return relationDO;
    }
}
