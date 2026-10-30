package com.quanxiaoha.xiaohashu.user.relation.biz;

import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.xiaohashu.user.dto.resp.FindUserByIdRspDTO;
import com.quanxiaoha.xiaohashu.user.relation.biz.config.RelationProperties;
import com.quanxiaoha.xiaohashu.user.relation.biz.model.dto.RelationCacheEntryDTO;
import com.quanxiaoha.xiaohashu.user.relation.biz.model.dto.RelationCacheSnapshotDTO;
import com.quanxiaoha.xiaohashu.user.relation.biz.model.vo.FindFansListReqVO;
import com.quanxiaoha.xiaohashu.user.relation.biz.model.vo.FindFollowingListReqVO;
import com.quanxiaoha.xiaohashu.user.relation.biz.model.vo.FindFollowingUserRspVO;
import com.quanxiaoha.xiaohashu.user.relation.biz.rpc.RelationCountRpcService;
import com.quanxiaoha.xiaohashu.user.relation.biz.rpc.UserRpcService;
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
    private UserRpcService userRpcService;
    private RelationCountRpcService relationCountRpcService;

    @BeforeEach
    void setup() {
        relationService = new RelationServiceImpl();
        relationListCache = mock(RelationListCache.class);
        userRpcService = mock(UserRpcService.class);
        relationCountRpcService = mock(RelationCountRpcService.class);
        RelationProperties relationProperties = new RelationProperties();
        relationProperties.setFansCacheSize(3);
        ReflectionTestUtils.setField(relationService, "relationListCache", relationListCache);
        ReflectionTestUtils.setField(relationService, "userRpcService", userRpcService);
        ReflectionTestUtils.setField(relationService, "relationProperties", relationProperties);
        ReflectionTestUtils.setField(relationService, "relationCountRpcService", relationCountRpcService);
    }

    @Test
    void followingListPreservesRelationOrderWhenRpcReturnsDifferentOrder() {
        RelationCacheSnapshotDTO cacheSnapshot = new RelationCacheSnapshotDTO(2,
                List.of(new RelationCacheEntryDTO(20, 200), new RelationCacheEntryDTO(10, 100)));
        when(relationListCache.load("following", 1, 0, 1)).thenReturn(cacheSnapshot);
        when(userRpcService.findByIds(List.of(20L, 10L))).thenReturn(List.of(user(10), user(20)));
        FindFollowingListReqVO request = FindFollowingListReqVO.builder().userId(1L).pageNo(1).pageSize(2).build();
        var response = relationService.findFollowingList(request);
        assertEquals(List.of(20L, 10L), response.getData().stream().map(FindFollowingUserRspVO::getUserId).toList());
        assertEquals(2, response.getTotalCount());
    }

    @Test
    void fansTotalAlwaysComesFromCounter() {
        when(relationListCache.load("followers", 1, 0, 1))
                .thenReturn(new RelationCacheSnapshotDTO(0, List.of()));
        when(relationCountRpcService.findFansTotal(1)).thenReturn(0L);
        var response = relationService.findFansList(fansRequest(1, 2));
        assertTrue(response.getData().isEmpty());
        assertEquals(0L, response.getTotalCount());
        verify(relationCountRpcService).findFansTotal(1);
        verifyNoInteractions(userRpcService);
    }

    @Test
    void truncatedFansCacheServesCachedPageAndCounterTotal() {
        when(relationListCache.load("followers", 1, 0, 1)).thenReturn(new RelationCacheSnapshotDTO(3,
                List.of(new RelationCacheEntryDTO(30, 300), new RelationCacheEntryDTO(20, 200))));
        when(relationCountRpcService.findFansTotal(1)).thenReturn(1000000L);
        when(userRpcService.findByIds(List.of(30L, 20L))).thenReturn(List.of(user(30), user(20)));
        var response = relationService.findFansList(fansRequest(1, 2));
        assertEquals(1000000L, response.getTotalCount());
        assertEquals(2, response.getData().size());
    }

    @Test
    void pageBeyondFanCacheLimitIsRejectedWithoutQueryingCache() {
        // fansCacheSize = 3，第 2 页起点 offset=2、页大小 2，越过上限，直接拒绝
        assertThrows(BizException.class, () -> relationService.findFansList(fansRequest(2, 2)));
        verifyNoInteractions(relationListCache, userRpcService, relationCountRpcService);
    }

    @Test
    void pageExactlyAtFanCacheLimitIsAllowed() {
        // pageNo=2、pageSize=1 → offset=1，取 [1,1]，正好不越过 fansCacheSize=3
        when(relationListCache.load("followers", 1, 1, 1))
                .thenReturn(new RelationCacheSnapshotDTO(3,
                        List.of(new RelationCacheEntryDTO(20, 200))));
        when(relationCountRpcService.findFansTotal(1)).thenReturn(3L);
        when(userRpcService.findByIds(List.of(20L))).thenReturn(List.of(user(20)));
        var response = relationService.findFansList(fansRequest(2, 1));
        assertEquals(1, response.getData().size());
        assertEquals(3L, response.getTotalCount());
    }

    private FindFansListReqVO fansRequest(int pageNo, int pageSize) {
        return FindFansListReqVO.builder().userId(1L).pageNo(pageNo).pageSize(pageSize).build();
    }

    private FindUserByIdRspDTO user(long userId) {
        FindUserByIdRspDTO userInfo = new FindUserByIdRspDTO();
        userInfo.setId(userId);
        return userInfo;
    }
}
