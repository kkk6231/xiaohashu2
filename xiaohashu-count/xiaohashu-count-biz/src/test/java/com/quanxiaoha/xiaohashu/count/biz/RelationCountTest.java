package com.quanxiaoha.xiaohashu.count.biz;

import com.quanxiaoha.xiaohashu.count.biz.consumer.CountEventBatchConsumer;
import com.quanxiaoha.xiaohashu.count.api.event.NoteCountEvent;
import com.quanxiaoha.xiaohashu.user.relation.api.event.RelationEvent;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class RelationCountTest {
    @Test
    void relationKeepsBothTargetsAndNegativeDeltas() {
        var event = new RelationEvent();
        event.setEventId(12L);
        event.setRelationId(1L);
        event.setFromUserId(10L);
        event.setToUserId(20L);
        event.setType(2);
        event.setRelationVersion(2L);
        event.setUpdateTime(LocalDateTime.now());
        var changes = CountEventBatchConsumer.relationChanges(event);
        assertEquals(2, changes.size());
        assertEquals(-1L, changes.get(0).getDeltas().get("followingTotal"));
        assertEquals(-1L, changes.get(1).getDeltas().get("fansTotal"));
        assertEquals(changes.get(0).getEventKey(), changes.get(1).getEventKey());
        assertNotEquals(changes.get(0).getTargetId(), changes.get(1).getTargetId());
    }

    @Test
    void deletionCombinesAllAuthorFields() {
        var event = new NoteCountEvent();
        event.setEventId(1L);
        event.setNoteId(2L);
        event.setAuthorId(3L);
        event.setEventType("NOTE_DELETED");
        event.setRemovedLikeTotal(10);
        event.setRemovedCollectTotal(4);
        var changes = CountEventBatchConsumer.noteChanges(event);
        assertEquals(1, changes.size());
        assertEquals(java.util.Map.of("worksTotal", -1L, "likesReceivedTotal", -10L,
                "collectsReceivedTotal", -4L), changes.get(0).getDeltas());
    }
}
