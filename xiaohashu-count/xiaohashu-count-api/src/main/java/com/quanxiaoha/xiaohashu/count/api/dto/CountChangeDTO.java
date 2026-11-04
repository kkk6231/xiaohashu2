package com.quanxiaoha.xiaohashu.count.api.dto;

import lombok.*;
import java.util.Map;

/** 一条业务事件对一个计数对象的实际增量。 */
@Data
@NoArgsConstructor
public class CountChangeDTO {
    private String eventKey;
    /** 由事件发生日期生成的 yyyyMMdd 去重分片。 */
    private String seenBucket;
    private String targetType;
    private Long targetId;
    private Map<String, Long> deltas;

    /** 构建一条业务事件对目标计数的实际增量。 */
    public CountChangeDTO(String eventKey, String seenBucket, String targetType, Long targetId,
            Map<String, Long> deltas) {
        this.eventKey = eventKey;
        this.seenBucket = seenBucket;
        this.targetType = targetType;
        this.targetId = targetId;
        this.deltas = deltas;
    }
}
