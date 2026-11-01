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
        RelationEvent event = RelationEvent.builder()
                .eventId(12L)
                .relationId(1L)
                .fromUserId(10L)
                .toUserId(20L)
                .type(2)
                .relationVersion(2L)
                .updateTime(LocalDateTime.now())
                .build();
        var changes = CountEventBatchConsumer.relationChanges(event);
        assertEquals(2, changes.size());
        assertEquals(-1L, changes.get(0).getDeltas().get("followingTotal"));
        assertEquals(-1L, changes.get(1).getDeltas().get("fansTotal"));
        assertEquals(changes.get(0).getEventKey(), changes.get(1).getEventKey());
        assertNotEquals(changes.get(0).getTargetId(), changes.get(1).getTargetId());
    }

    @Test
    void deletionCombinesAllAuthorFields() {
        NoteCountEvent event = NoteCountEvent.builder()
                .eventId(1L)
                .noteId(2L)
                .authorId(3L)
                .eventType("NOTE_DELETED")
                .removedLikeTotal(10)
                .removedCollectTotal(4)
                .build();
        var changes = CountEventBatchConsumer.noteChanges(event);
        assertEquals(1, changes.size());
        assertEquals(java.util.Map.of("worksTotal", -1L, "likesReceivedTotal", -10L,
                "collectsReceivedTotal", -4L), changes.get(0).getDeltas());
    }
}
