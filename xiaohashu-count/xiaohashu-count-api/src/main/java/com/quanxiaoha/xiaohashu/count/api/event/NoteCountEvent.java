package com.quanxiaoha.xiaohashu.count.api.event;

import lombok.Data;

// 兼容旧消息中的已移除字段，不参与当前计数逻辑。
@com.fasterxml.jackson.annotation.JsonIgnoreProperties("countGeneration")
@Data
public class NoteCountEvent {
    public static final String TOPIC = "NoteCountChangedV1";
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
