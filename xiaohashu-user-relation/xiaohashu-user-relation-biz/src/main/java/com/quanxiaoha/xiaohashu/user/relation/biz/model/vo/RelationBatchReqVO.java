package com.quanxiaoha.xiaohashu.user.relation.biz.model.vo;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Data
public class RelationBatchReqVO {
    @NotNull @Positive private Long userId;

    @NotNull
    @Size(max = 100)
    @JsonAlias("candidateUserIds")
    private List<@NotNull @Positive Long> targetUserIds;
}
