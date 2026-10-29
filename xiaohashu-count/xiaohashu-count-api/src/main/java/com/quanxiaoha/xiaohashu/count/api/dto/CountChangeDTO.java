package com.quanxiaoha.xiaohashu.count.api.dto;

import lombok.*;
import java.util.Map;

/** 一条业务事件对一个计数对象的实际增量。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CountChangeDTO {
    private Long countGeneration;
    private String eventKey;
    private String targetType;
    private Long targetId;
    private Map<String, Long> deltas;

    /** 兼容第一代内部调用；不会随当前代次变化。 */
    public CountChangeDTO(String eventKey, String targetType, Long targetId, Map<String, Long> deltas) {
        this(1L, eventKey, targetType, targetId, deltas);
    }
}
