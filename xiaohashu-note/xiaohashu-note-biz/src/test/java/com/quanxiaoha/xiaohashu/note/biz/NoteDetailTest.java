package com.quanxiaoha.xiaohashu.note.biz;

import com.quanxiaoha.framework.biz.context.holder.LoginUserContextHolder;
import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.framework.common.util.JsonUtils;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.*;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.*;
import com.quanxiaoha.xiaohashu.note.biz.rpc.*;
import com.quanxiaoha.xiaohashu.note.biz.service.impl.NoteServiceImpl;
import com.quanxiaoha.xiaohashu.user.dto.resp.FindUserByIdRspDTO;
import com.quanxiaoha.xiaohashu.count.api.dto.NoteCountDTO;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证详情聚合的并发、失败策略与共享缓存边界。 */
class NoteDetailTest {
    private NoteServiceImpl service;
    private ThreadPoolTaskExecutor executor;
    private UserRpcService users;
    private KeyValueRpcService contents;
    private CountRpcService counts;
    private RelationRpcService relations;
    private ValueOperations<String, String> cache;
    private NoteDOMapper notes;
    private NoteDO note;
    private FindNoteDetailReqVO request;

    /** 构建独立线程池及下游替身，不依赖真实 Redis 和微服务。 */
    @BeforeEach
    void setup() {
        service = new NoteServiceImpl();
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.initialize();
        users = mock(UserRpcService.class);
        contents = mock(KeyValueRpcService.class);
        counts = mock(CountRpcService.class);
        relations = mock(RelationRpcService.class);
        notes = mock(NoteDOMapper.class);
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        cache = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(cache);
        ReflectionTestUtils.setField(service, "threadPoolTaskExecutor", executor);
        ReflectionTestUtils.setField(service, "userRpcService", users);
        ReflectionTestUtils.setField(service, "keyValueRpcService", contents);
        ReflectionTestUtils.setField(service, "countRpcService", counts);
        ReflectionTestUtils.setField(service, "relationRpcService", relations);
        ReflectionTestUtils.setField(service, "noteDOMapper", notes);
        ReflectionTestUtils.setField(service, "redisTemplate", redis);
        ReflectionTestUtils.setField(service, "noteLikeDOMapper", mock(NoteLikeDOMapper.class));
        ReflectionTestUtils.setField(service, "noteCollectionDOMapper", mock(NoteCollectionDOMapper.class));
        LoginUserContextHolder.setUserId(30L);
        note = NoteDO.builder().id(981234L).creatorId(20L).status(1).visible(0)
                .isContentEmpty(false).contentUuid("content-id").build();
        when(notes.selectByPrimaryKey(note.getId())).thenReturn(note);
        request = FindNoteDetailReqVO.builder().id(note.getId()).build();
        FindUserByIdRspDTO author = new FindUserByIdRspDTO();
        author.setNickName("作者");
        when(users.findById(20L)).thenReturn(author);
        when(contents.findNoteContent("content-id")).thenReturn("正文");
        when(counts.findNote(note.getId())).thenReturn(new NoteCountDTO());
        when(relations.isFollowing(30L, 20L)).thenReturn(true);
    }

    /** 关闭测试线程池并清除当前用户，防止测试之间相互影响。 */
    @AfterEach
    void close() {
        executor.shutdown();
        LoginUserContextHolder.remove();
        com.github.benmanes.caffeine.cache.Cache<?, ?> localCache =
                (com.github.benmanes.caffeine.cache.Cache<?, ?>) ReflectionTestUtils.getField(service, "LOCAL_CACHE");
        localCache.invalidateAll();
    }

    /** 四个 RPC 均已启动才能返回，同时检查作者参数和缓存没有个人关注状态。 */
    @Test
    void startsFourCallsBeforeWaitingAndCachesOnlyPublicDetail() throws Exception {
        CountDownLatch started = new CountDownLatch(4);
        when(users.findById(20L)).thenAnswer(invocation -> {
            started.countDown();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            FindUserByIdRspDTO author = new FindUserByIdRspDTO();
            author.setNickName("作者");
            return author;
        });
        when(contents.findNoteContent("content-id")).thenAnswer(invocation -> {
            started.countDown();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            return "正文";
        });
        when(counts.findNote(note.getId())).thenAnswer(invocation -> {
            started.countDown();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            return new NoteCountDTO();
        });
        when(relations.isFollowing(30L, 20L)).thenAnswer(invocation -> {
            started.countDown();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            return true;
        });
        FindNoteDetailRspVO detail = service.findNoteDetail(request).getData();
        assertEquals("作者", detail.getCreatorName());
        assertTrue(detail.getFollowing());
        assertTrue(detail.isCountsAvailable());
        verify(users, never()).findById(30L);
        ArgumentCaptor<String> cachedJson = ArgumentCaptor.forClass(String.class);
        verify(cache, timeout(1000)).set(anyString(), cachedJson.capture(), anyLong(), eq(TimeUnit.SECONDS));
        FindNoteDetailRspVO cached = JsonUtils.parseObject(cachedJson.getValue(), FindNoteDetailRspVO.class);
        assertNull(cached.getFollowing());
        assertNull(cached.getLikeTotal());
    }

    /** 正文失败必须返回明确业务异常，不能把缺失正文作为成功详情缓存。 */
    @Test
    void contentFailureReturnsBusinessError() {
        when(contents.findNoteContent("content-id")).thenThrow(new IllegalStateException("KV unavailable"));
        BizException failure = assertThrows(BizException.class, () -> service.findNoteDetail(request));
        assertEquals("NOTE-20012", failure.getErrorCode());
        verify(cache, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    /** 作者资料无缓存且查询失败时返回明确错误。 */
    @Test
    void missingAuthorReturnsBusinessError() {
        when(users.findById(20L)).thenReturn(null);
        BizException failure = assertThrows(BizException.class, () -> service.findNoteDetail(request));
        assertEquals("NOTE-20013", failure.getErrorCode());
    }

    /** 计数和关系失败仅将对应字段标记未知，不影响正文展示。 */
    @Test
    void optionalFailuresReturnUnknownStates() {
        when(counts.findNote(note.getId())).thenThrow(new IllegalStateException("count unavailable"));
        when(relations.isFollowing(30L, 20L)).thenThrow(new IllegalStateException("relation unavailable"));
        FindNoteDetailRspVO detail = service.findNoteDetail(request).getData();
        assertEquals("正文", detail.getContent());
        assertFalse(detail.isCountsAvailable());
        assertNull(detail.getLikeTotal());
        assertNull(detail.getCollectTotal());
        assertFalse(detail.isFollowingAvailable());
        assertNull(detail.getFollowing());
    }

    /** 下游不及时返回时按聚合超时降级，并在结束后释放模拟阻塞任务。 */
    @Test
    void relationTimeoutDoesNotFailDetail() {
        ReflectionTestUtils.setField(service, "detailRpcTimeoutMillis", 300L);
        CountDownLatch release = new CountDownLatch(1);
        when(relations.isFollowing(30L, 20L)).thenAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return true;
        });
        try {
            FindNoteDetailRspVO detail = service.findNoteDetail(request).getData();
            assertFalse(detail.isFollowingAvailable());
            assertNull(detail.getFollowing());
        } finally {
            release.countDown();
        }
    }

    /** 共享缓存可直接复用正文和作者，匿名访问不发起关系查询。 */
    @Test
    void cachedAnonymousDetailSkipsCoreRpcAndRelation() {
        LoginUserContextHolder.remove();
        FindNoteDetailRspVO cached = FindNoteDetailRspVO.builder().id(note.getId()).creatorId(20L)
                .creatorName("缓存作者").content("缓存正文").visible(0).build();
        when(cache.get(anyString())).thenReturn(JsonUtils.toJsonString(cached));
        FindNoteDetailRspVO detail = service.findNoteDetail(request).getData();
        assertEquals("缓存正文", detail.getContent());
        assertFalse(detail.getFollowing());
        verifyNoInteractions(users, contents, relations);
    }
}
