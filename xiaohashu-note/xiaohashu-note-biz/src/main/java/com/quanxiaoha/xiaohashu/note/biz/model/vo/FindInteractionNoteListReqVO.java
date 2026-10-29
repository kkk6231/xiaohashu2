package com.quanxiaoha.xiaohashu.note.biz.model.vo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 当前登录用户的点赞、收藏列表分页参数。 */
@Data
public class FindInteractionNoteListReqVO {
    @NotNull
    @Min(1)
    private Integer pageNo = 1;

    @NotNull
    @Min(1)
    @Max(100)
    private Integer pageSize = 10;
}
