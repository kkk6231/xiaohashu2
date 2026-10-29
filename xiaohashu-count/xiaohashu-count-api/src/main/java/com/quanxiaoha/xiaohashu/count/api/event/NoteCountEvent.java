package com.quanxiaoha.xiaohashu.count.api.event;

import lombok.Data;

@Data
public class NoteCountEvent {
    /** 历史事件固定归属第一代；新事件在事实事务中设置代次。 */
    private Long countGeneration = 1L;
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
