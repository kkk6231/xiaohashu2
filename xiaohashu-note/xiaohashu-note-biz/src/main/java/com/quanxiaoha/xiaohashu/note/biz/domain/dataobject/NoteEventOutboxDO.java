package com.quanxiaoha.xiaohashu.note.biz.domain.dataobject;

import lombok.Data;

@Data
public class NoteEventOutboxDO {
    private Long id;
    private Long noteId;
    private String eventType;
    private String payload;
    private String claimToken;
}
