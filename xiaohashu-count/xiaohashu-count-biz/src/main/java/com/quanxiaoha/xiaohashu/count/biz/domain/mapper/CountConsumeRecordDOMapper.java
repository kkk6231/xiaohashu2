package com.quanxiaoha.xiaohashu.count.biz.domain.mapper;

import com.quanxiaoha.xiaohashu.count.biz.domain.dataobject.CountConsumeRecordDO;

/**
 * 计数 MQ 消费记录 Mapper。
 */
public interface CountConsumeRecordDOMapper {

    /**
     * 插入计数 MQ 消费记录。
     *
     * <p>数据库需要在 consumer_type + message_id 上建立唯一索引。
     * 重复插入时会抛出 DuplicateKeyException，消费者据此识别重复消费并跳过计数更新。</p>
     *
     * @param record 消费记录
     * @return 影响行数
     */
    int insert(CountConsumeRecordDO record);
}
