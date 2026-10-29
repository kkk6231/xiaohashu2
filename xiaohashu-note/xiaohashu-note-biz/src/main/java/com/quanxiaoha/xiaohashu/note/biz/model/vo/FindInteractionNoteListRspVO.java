package com.quanxiaoha.xiaohashu.note.biz.model.vo;

import java.time.LocalDateTime;
import lombok.Data;

/** 点赞、收藏列表中的笔记摘要；不加载正文及详情页聚合信息。 */
@Data
public class FindInteractionNoteListRspVO {
    private Long noteId;
    private String title;
    private Long creatorId;
    private Integer type;
    private String imgUris;
    private String videoUri;
    private LocalDateTime interactionTime;
}
