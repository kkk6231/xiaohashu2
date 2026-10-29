package com.quanxiaoha.xiaohashu.count.api.dto;

import lombok.*;
import java.util.Map;

/** 一条业务事件对一个计数对象的实际增量。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CountChangeDTO {
    private String eventKey;
    private String targetType;
    private Long targetId;
    private Map<String, Long> deltas;
}
