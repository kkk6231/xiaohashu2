package com.quanxiaoha.xiaohashu.user.relation.biz;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.quanxiaoha.xiaohashu.user.relation.biz.config.RelationProperties;
import com.quanxiaoha.xiaohashu.user.relation.biz.domain.dataobject.RelationOutboxDO;
import com.quanxiaoha.xiaohashu.user.relation.biz.domain.mapper.RelationOutboxDOMapper;
import com.quanxiaoha.xiaohashu.user.relation.biz.service.impl.RelationOutboxPublisher;

import org.apache.rocketmq.client.producer.*;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.*;

class RelationPublisherTest {
    @Test
    void brokerFailureNeverMarksSent() {
        run(SendStatus.FLUSH_DISK_TIMEOUT, false);
    }

    @Test
    void onlySendOkAcknowledgesOutbox() {
        run(SendStatus.SEND_OK, true);
    }

    @Test
    void transportExceptionNeverMarksSent() {
        run(null, false);
    }

    private void run(SendStatus status, boolean sent) {
        RelationOutboxDOMapper mapper = mock(RelationOutboxDOMapper.class);
        RocketMQTemplate mq = mock(RocketMQTemplate.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any()))
                .thenAnswer(
                        i ->
                                ((TransactionCallback<?>) i.getArgument(0))
                                        .doInTransaction(new SimpleTransactionStatus()));
        RelationOutboxDO row = new RelationOutboxDO();
        row.setEventId(1L);
        row.setRelationId(2L);
        row.setRetryCount(0);
        row.setPayload("{}");
        when(mapper.head(2)).thenReturn(row);
        when(mapper.claim(eq(1L), anyString(), anyLong())).thenReturn(1);
        SendResult result = new SendResult();
        result.setSendStatus(status);
        when(mq.syncSendOrderly(
                        anyString(),
                        any(org.springframework.messaging.Message.class),
                        anyString(),
                        anyLong()))
                .thenReturn(result);
        if (status == null) {
            when(mq.syncSendOrderly(anyString(), any(org.springframework.messaging.Message.class),
                    anyString(), anyLong())).thenThrow(new IllegalStateException("connection lost"));
        }
        try (var ignored =
                new ClosingPublisher(
                        wire(
                                RelationOutboxPublisher.class,
                                mapper,
                                tx,
                                mq,
                                new RelationProperties()))) {
            ignored.publisher.publish(2);
            if (sent) {
                verify(mapper).sent(eq(1L), anyString());
                verify(mapper, never()).failed(anyLong(), anyString(), anyInt(), anyString());
            } else {
                verify(mapper, never()).sent(anyLong(), anyString());
                verify(mapper).failed(eq(1L), anyString(), anyInt(), anyString());
            }
        }
    }

    record ClosingPublisher(RelationOutboxPublisher publisher) implements AutoCloseable {
        public void close() {
            publisher.close();
        }
    }

    private static <T> T wire(Class<T> type, Object... dependencies) {
        try {
            T instance = type.getDeclaredConstructor().newInstance();
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (!field.isAnnotationPresent(jakarta.annotation.Resource.class)) continue;
                Object dependency =
                        java.util.Arrays.stream(dependencies)
                                .filter(field.getType()::isInstance)
                                .findFirst()
                                .orElseThrow();
                field.setAccessible(true);
                field.set(instance, dependency);
            }
            return instance;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
