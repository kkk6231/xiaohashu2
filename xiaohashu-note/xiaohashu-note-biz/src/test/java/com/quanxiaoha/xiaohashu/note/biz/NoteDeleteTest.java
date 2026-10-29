package com.quanxiaoha.xiaohashu.note.biz;

import com.quanxiaoha.framework.biz.context.holder.LoginUserContextHolder;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteInteractionMapper;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.DeleteNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.service.impl.NoteCountEventService;
import com.quanxiaoha.xiaohashu.note.biz.service.impl.NoteServiceImpl;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;

/** 删除入口只允许状态迁移成功者生成计数和缓存事件。 */
class NoteDeleteTest {
    private NoteServiceImpl service;
    private NoteDOMapper noteMapper;
    private NoteInteractionMapper interactionMapper;
    private NoteCountEventService events;
    private RocketMQTemplate mq;
    private DeleteNoteReqVO request;

    /** 准备笔记及外围依赖，两个请求均可读到相同的旧状态。 */
    @BeforeEach
    void setup() {
        service = new NoteServiceImpl();
        noteMapper = mock(NoteDOMapper.class);
        interactionMapper = mock(NoteInteractionMapper.class);
        events = mock(NoteCountEventService.class);
        mq = mock(RocketMQTemplate.class);
        JdbcTemplate generationJdbc = mock(JdbcTemplate.class);
        when(generationJdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);
        ReflectionTestUtils.setField(service, "countJdbcTemplate", generationJdbc);
        ReflectionTestUtils.setField(service, "noteDOMapper", noteMapper);
        ReflectionTestUtils.setField(service, "noteInteractionMapper", interactionMapper);
        ReflectionTestUtils.setField(service, "noteCountEventService", events);
        ReflectionTestUtils.setField(service, "rocketMQTemplate", mq);
        when(interactionMapper.findNote(10L)).thenReturn(
                NoteDO.builder().id(10L).creatorId(20L).status(1).build());
        request = new DeleteNoteReqVO();
        request.setId(10L);
        LoginUserContextHolder.setUserId(20L);
    }

    /** 清理线程登录身份。 */
    @AfterEach
    void cleanup() {
        LoginUserContextHolder.remove();
    }

    /** 两个请求都读取旧状态时，只由更新成功的请求写一次删除事件。 */
    @Test
    void staleReadDoesNotCreateDuplicateDeletionEvents() {
        when(noteMapper.markDeletedIfStatus(eq(10L), eq(20L), eq(1), any())).thenReturn(1, 0);
        service.deleteNote(request);
        service.deleteNote(request);
        verify(events, times(1)).append(any(NoteDO.class), eq("NOTE_DELETED"), eq(0L), eq(0L));
        verify(events, times(1)).appendCacheInvalidation(10L);
        verify(interactionMapper, times(1)).countLikes(10L);
        verify(interactionMapper, times(1)).countCollections(10L);
    }

    /** 重复删除已删除笔记，直接成功且不再更新或发送事件。 */
    @Test
    void alreadyDeletedNoteHasNoSideEffects() {
        when(interactionMapper.findNote(10L)).thenReturn(
                NoteDO.builder().id(10L).creatorId(20L).status(2).build());
        service.deleteNote(request);
        verifyNoInteractions(noteMapper, events, mq);
    }
}
