package com.quanxiaoha.xiaohashu.note.biz;

import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.*;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteInteractionMapper;
import com.quanxiaoha.xiaohashu.note.biz.model.dto.NoteInteractionCommandDTO;
import com.quanxiaoha.xiaohashu.note.biz.service.impl.NoteInteractionService;
import com.quanxiaoha.xiaohashu.note.biz.rpc.CountRpcService;
import com.quanxiaoha.xiaohashu.count.api.CountFeignApi;
import com.quanxiaoha.framework.common.response.Response;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NoteInteractionTest {
    private NoteInteractionMapper mapper;
    private NoteInteractionService service;

    @BeforeEach
    void setup() {
        mapper = mock(NoteInteractionMapper.class);
        service = new NoteInteractionService();
        ReflectionTestUtils.setField(service, "interactionMapper", mapper);
        when(mapper.findNote(10)).thenReturn(NoteDO.builder().id(10L).creatorId(20L).status(1).visible(0).build());
        when(mapper.findResults(anyList())).thenReturn(List.of());
        when(mapper.findLikes(anyList())).thenReturn(List.of());
        when(mapper.findCollections(anyList())).thenReturn(List.of());
    }

    private NoteInteractionCommandDTO command(boolean state) {
        return new NoteInteractionCommandDTO(UUID.randomUUID().toString(), 20L, 10L, "LIKE", state);
    }

    @Test
    void commandsFoldFinalFactButKeepEachResult() {
        var results = service.execute(List.of(command(true), command(false), command(true)));
        assertEquals(List.of(1, -1, 1), results.stream().map(NoteInteractionResultDO::getActualDelta).toList());
        ArgumentCaptor<List<NoteLikeDO>> rows = ArgumentCaptor.forClass(List.class);
        verify(mapper).saveLikes(rows.capture());
        assertEquals(1, rows.getValue().size());
        assertEquals(1, rows.getValue().get(0).getStatus());
        verify(mapper).saveResults(results);
    }

    @Test
    void retryAfterDeletionReusesOriginalResult() {
        var command = command(true);
        var result = service.execute(List.of(command)).get(0);
        clearInvocations(mapper);
        when(mapper.findResults(anyList())).thenReturn(List.of(result));
        when(mapper.findNote(10)).thenReturn(NoteDO.builder().id(10L).creatorId(20L).status(2).build());
        var retry = service.execute(List.of(command));
        assertSame(result, retry.get(0));
        assertEquals(1, retry.get(0).getActualDelta());
        verify(mapper, never()).saveLikes(anyList());
        verify(mapper, never()).saveResults(anyList());
    }

    @Test
    void duplicateAndRejectedCommandsDoNotAddNewChanges() {
        var command = command(true);
        var results = service.execute(List.of(command, command));
        assertSame(results.get(0), results.get(1));
        when(mapper.findNote(10)).thenReturn(NoteDO.builder().id(10L).creatorId(20L).status(2).build());
        var rejected = service.execute(List.of(command(false))).get(0);
        assertEquals("REJECTED", rejected.getResultType());
        assertEquals(0, rejected.getActualDelta());
    }

    @Test
    void rpcFailurePropagatesAndRetryKeepsSameIdentity() {
        var result = service.execute(List.of(command(true)));
        var api = mock(CountFeignApi.class);
        var rpc = new CountRpcService();
        ReflectionTestUtils.setField(rpc, "countFeignApi", api);
        when(api.apply(any(), any())).thenReturn(Response.fail()).thenReturn(Response.success());
        assertThrows(IllegalStateException.class, () -> rpc.applyInteractionResults(result));
        rpc.applyInteractionResults(result);
        var captor = ArgumentCaptor.forClass(com.quanxiaoha.xiaohashu.count.api.dto.CountChangeBatchDTO.class);
        verify(api, times(2)).apply(captor.capture(), any());
        assertEquals(captor.getAllValues().get(0), captor.getAllValues().get(1));
        assertEquals(2, captor.getValue().getChanges().size());
    }
}
