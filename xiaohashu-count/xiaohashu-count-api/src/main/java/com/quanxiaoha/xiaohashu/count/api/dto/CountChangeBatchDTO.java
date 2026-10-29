package com.quanxiaoha.xiaohashu.count.api.dto;

import lombok.*;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CountChangeBatchDTO {
    private List<CountChangeDTO> changes;
}
