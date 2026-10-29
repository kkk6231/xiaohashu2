package com.quanxiaoha.xiaohashu.note.biz.model.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NoteInteractionCommandDTO {
    public static final String TOPIC = "NoteInteractionCommandV1";
    private String requestId;
    private Long userId;
    private Long noteId;
    private String actionType;
    private Boolean targetState;

    public void validate() {
        if (requestId == null || requestId.length() != 36 || userId == null || userId <= 0
                || noteId == null || noteId <= 0 || targetState == null
                || !("LIKE".equals(actionType) || "COLLECT".equals(actionType))) {
            throw new IllegalArgumentException("互动命令参数不完整");
        }
        java.util.UUID.fromString(requestId);
    }

    public String identity() {
        return userId + ":" + requestId;
    }
}
