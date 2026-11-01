package com.quanxiaoha.xiaohashu.count.api.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NoteCountEvent {
    public static final String TOPIC = "NoteCountChangedV1";
    @Builder.Default
    private String source = "note";
    private Long eventId;
    private String eventType;
    private Long noteId;
    private Long authorId;
    private Long actorId;
    private String occurredAt;
    private long removedLikeTotal;
    private long removedCollectTotal;
}
