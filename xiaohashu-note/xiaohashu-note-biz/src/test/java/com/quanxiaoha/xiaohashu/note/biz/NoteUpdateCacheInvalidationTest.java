package com.quanxiaoha.xiaohashu.note.biz;

import com.quanxiaoha.framework.biz.context.holder.LoginUserContextHolder;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.TopicDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.UpdateNoteReqVO;
import com.quanxiaoha.xiaohashu.note.biz.rpc.KeyValueRpcService;
import com.quanxiaoha.xiaohashu.note.biz.service.impl.NoteCountEventService;
import com.quanxiaoha.xiaohashu.note.biz.service.impl.NoteServiceImpl;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 更新笔记通过事务 Outbox 可靠地失效详情缓存。 */
class NoteUpdateCacheInvalidationTest {
    private NoteServiceImpl noteService;
    private NoteDOMapper noteDOMapper;
    private KeyValueRpcService keyValueRpcService;
    private NoteCountEventService noteCountEventService;
    private RedisTemplate<String, String> redisTemplate;
    private RocketMQTemplate rocketMQTemplate;
    private UpdateNoteReqVO request;

    /** 准备一篇属于当前用户的图文笔记及更新依赖。 */
    @BeforeEach
    void setup() {
        noteService = new NoteServiceImpl();
        noteDOMapper = mock(NoteDOMapper.class);
        keyValueRpcService = mock(KeyValueRpcService.class);
        noteCountEventService = mock(NoteCountEventService.class);
        redisTemplate = mock(RedisTemplate.class);
        rocketMQTemplate = mock(RocketMQTemplate.class);

        TopicDOMapper topicDOMapper = mock(TopicDOMapper.class);
        ReflectionTestUtils.setField(noteService, "noteDOMapper", noteDOMapper);
        ReflectionTestUtils.setField(noteService, "topicDOMapper", topicDOMapper);
        ReflectionTestUtils.setField(noteService, "keyValueRpcService", keyValueRpcService);
        ReflectionTestUtils.setField(noteService, "noteCountEventService", noteCountEventService);
        ReflectionTestUtils.setField(noteService, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(noteService, "rocketMQTemplate", rocketMQTemplate);

        NoteDO existingNote = NoteDO.builder()
                .id(10L)
                .creatorId(20L)
                .contentUuid("content-10")
                .build();
        when(noteDOMapper.selectByPrimaryKey(10L)).thenReturn(existingNote);
        when(keyValueRpcService.saveNoteContent("content-10", "new content")).thenReturn(true);

        request = UpdateNoteReqVO.builder()
                .id(10L)
                .type(0)
                .imgUris(List.of("image-1"))
                .title("new title")
                .content("new content")
                .build();
        LoginUserContextHolder.setUserId(20L);
    }

    /** 清理当前测试线程中的登录用户。 */
    @AfterEach
    void cleanup() {
        LoginUserContextHolder.remove();
    }

    /** 更新成功后只登记 Outbox，不在业务事务内直接操作 Redis 或发送广播。 */
    @Test
    void successfulUpdateAppendsCacheInvalidationTask() {
        noteService.updateNote(request);

        verify(noteDOMapper).updateByPrimaryKey(any(NoteDO.class));
        verify(keyValueRpcService).saveNoteContent("content-10", "new content");
        verify(noteCountEventService).appendCacheInvalidation(10L);
        verifyNoInteractions(redisTemplate, rocketMQTemplate);
    }

    /** KV 正文更新失败时不登记缓存失效任务，事务由业务异常回滚。 */
    @Test
    void contentUpdateFailureDoesNotAppendCacheInvalidationTask() {
        when(keyValueRpcService.saveNoteContent("content-10", "new content")).thenReturn(false);

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> noteService.updateNote(request));

        verify(noteCountEventService, never()).appendCacheInvalidation(anyLong());
        verifyNoInteractions(redisTemplate, rocketMQTemplate);
    }
}
