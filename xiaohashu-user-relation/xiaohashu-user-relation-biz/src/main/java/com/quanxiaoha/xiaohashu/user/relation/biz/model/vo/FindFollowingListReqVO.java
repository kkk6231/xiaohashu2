package com.quanxiaoha.xiaohashu.user.relation.biz.model.vo;

import jakarta.validation.constraints.*;
import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FindFollowingListReqVO {
    @NotNull @Positive private Long userId;

    @NotNull
    @Min(1)
    @Builder.Default
    private Integer pageNo = 1;

    @NotNull
    @Min(1)
    @Max(100)
    @Builder.Default
    private Integer pageSize = 20;
}
