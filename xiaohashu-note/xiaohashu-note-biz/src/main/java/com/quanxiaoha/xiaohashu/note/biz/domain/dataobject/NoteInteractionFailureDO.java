package com.quanxiaoha.xiaohashu.note.biz.domain.dataobject;

import lombok.Data;

/** 永久非法的 MQ 命令原文及来源，持久化成功后才允许跳过。 */
@Data
public class NoteInteractionFailureDO {
    private String messageKey;
    private String topic;
    private String brokerMessageId;
    private Integer queueId;
    private Long queueOffset;
    private byte[] body;
    private String reasonCode;
}
