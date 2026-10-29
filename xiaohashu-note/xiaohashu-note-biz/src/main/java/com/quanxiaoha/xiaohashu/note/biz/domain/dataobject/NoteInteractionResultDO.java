package com.quanxiaoha.xiaohashu.note.biz.domain.dataobject;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class NoteInteractionResultDO {
    private Long userId;
    private String requestId;
    private Long noteId;
    private String actionType;
    private Boolean targetState;
    private Long authorId;
    private String resultType;
    private String reasonCode;
    private Integer actualDelta;
    private LocalDateTime createdAt;
}
