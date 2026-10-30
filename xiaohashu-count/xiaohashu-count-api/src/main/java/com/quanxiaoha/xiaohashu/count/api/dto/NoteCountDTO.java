package com.quanxiaoha.xiaohashu.count.api.dto;

import lombok.Data;

@Data
public class NoteCountDTO {
    private long noteId;
    private Long likeTotal;
    private Long collectTotal;
}
