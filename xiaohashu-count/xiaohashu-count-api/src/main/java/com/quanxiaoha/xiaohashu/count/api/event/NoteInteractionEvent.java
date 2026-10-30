package com.quanxiaoha.xiaohashu.count.api.event;

import lombok.Data;

/** 点赞或收藏操作事件；相同消息重投时 eventId 必须保持不变。 */
@Data
public class NoteInteractionEvent {
    public static final String TOPIC = "NoteInteractionEventV2";
    private String eventId;
    private Long userId;
    private Long noteId;
    private Long authorId;
    private String actionType;
    private Boolean targetState;

    /** 校验事件，避免非法消息错误更新计数。 */
    public void validate() {
        if (eventId == null || eventId.isBlank() || userId == null || userId <= 0
                || noteId == null || noteId <= 0 || authorId == null || authorId <= 0
                || targetState == null || !("LIKE".equals(actionType) || "COLLECT".equals(actionType))) {
            throw new IllegalArgumentException("互动事件参数不完整");
        }
    }
}
