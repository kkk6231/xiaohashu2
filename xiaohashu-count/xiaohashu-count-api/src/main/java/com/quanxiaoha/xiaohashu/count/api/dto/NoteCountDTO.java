package com.quanxiaoha.xiaohashu.count.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NoteCountDTO {
    private long noteId;
    private Long likeTotal;
    private Long collectTotal;
}
