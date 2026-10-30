package com.quanxiaoha.xiaohashu.note.biz;

import com.quanxiaoha.framework.biz.context.holder.LoginUserContextHolder;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteLikeDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.FindInteractionNoteListReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.FindInteractionNoteListRspVO;
import com.quanxiaoha.xiaohashu.note.biz.service.impl.NoteServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.LinkedHashSet;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NoteInteractionCacheListTest {
    /** 清除登录状态。 */
    @AfterEach
    void cleanup() { LoginUserContextHolder.remove(); }

    /** 最近列表缓存保留 ZSet 顺序，过滤不可见笔记。 */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void returnsVisibleCachedNotesInOrder() {
        NoteServiceImpl service = new NoteServiceImpl();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.size(anyString())).thenReturn(2L);
        LinkedHashSet entries = new LinkedHashSet();
        entries.add(new DefaultTypedTuple<>("20", 2000.0));
        entries.add(new DefaultTypedTuple<>("10", 1000.0));
        when(zset.reverseRangeWithScores(anyString(), eq(0L), eq(9L))).thenReturn(entries);
        NoteLikeDOMapper likeMapper = mock(NoteLikeDOMapper.class);
        when(likeMapper.countVisibleNotes(1L)).thenReturn(2L);
        NoteDOMapper noteMapper = mock(NoteDOMapper.class);
        FindInteractionNoteListRspVO row = new FindInteractionNoteListRspVO();
        row.setNoteId(10L);
        when(noteMapper.selectVisibleNotesByIds(eq(1L), anyList())).thenReturn(List.of(row));
        ReflectionTestUtils.setField(service, "interactionRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "noteLikeDOMapper", likeMapper);
        ReflectionTestUtils.setField(service, "noteDOMapper", noteMapper);
        LoginUserContextHolder.setUserId(1L);
        FindInteractionNoteListReqVO request = new FindInteractionNoteListReqVO();
        var result = service.findLikedNotes(request);
        assertEquals(1, result.getData().size());
        assertEquals(10L, result.getData().get(0).getNoteId());
        assertNotNull(result.getData().get(0).getInteractionTime());
        verify(likeMapper, never()).selectVisibleNotesPage(anyLong(), anyLong(), anyInt());
    }
    /** 缓存明显缺失时，即使当前页在缓存范围内也应回源。 */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void incompleteCacheFallsBackToDatabase() {
        NoteServiceImpl service = new NoteServiceImpl();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.size(anyString())).thenReturn(9L);
        NoteLikeDOMapper likeMapper = mock(NoteLikeDOMapper.class);
        when(likeMapper.countVisibleNotes(1L)).thenReturn(10L);
        when(likeMapper.selectVisibleNotesPage(1L, 0L, 5)).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "interactionRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "noteLikeDOMapper", likeMapper);
        LoginUserContextHolder.setUserId(1L);
        FindInteractionNoteListReqVO request = new FindInteractionNoteListReqVO();
        request.setPageSize(5);
        service.findLikedNotes(request);
        verify(likeMapper).selectVisibleNotesPage(1L, 0L, 5);
        verify(zset, never()).reverseRangeWithScores(anyString(), anyLong(), anyLong());
    }
}
