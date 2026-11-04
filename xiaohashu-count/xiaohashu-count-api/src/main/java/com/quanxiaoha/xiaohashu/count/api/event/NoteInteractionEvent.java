package com.quanxiaoha.xiaohashu.count.api.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 点赞或收藏操作事件；相同消息重投时 eventId 必须保持不变。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NoteInteractionEvent {
    public static final String TOPIC = "NoteInteractionEventV2";
    private String eventId;
    private Long userId;
    private Long noteId;
    private Long authorId;
    private String actionType;
    private Boolean targetState;
    /** 事件首次创建时间；重投时保持不变，用于生成计数去重分片。 */
    private String occurredAt;

    /** 校验事件，避免非法消息错误更新计数。 */
    public void validate() {
        if (eventId == null || eventId.isBlank() || userId == null || userId <= 0
                || noteId == null || noteId <= 0 || authorId == null || authorId <= 0
                || targetState == null || !("LIKE".equals(actionType) || "COLLECT".equals(actionType))) {
            throw new IllegalArgumentException("互动事件参数不完整");
        }
    }
}
