package com.quanxiaoha.xiaohashu.note.biz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanxiaoha.xiaohashu.note.biz.consumer.NoteInteractionConsumer;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteInteractionResultDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteInteractionMapper;
import com.quanxiaoha.xiaohashu.note.biz.model.dto.NoteInteractionCommandDTO;
import com.quanxiaoha.xiaohashu.note.biz.rpc.CountRpcService;
import com.quanxiaoha.xiaohashu.note.biz.service.impl.NoteInteractionCountCompensator;
import com.quanxiaoha.xiaohashu.note.biz.service.impl.NoteInteractionService;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentCaptor;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用实际事务代理和 Mapper 验证消费确认、异常隔离、计数补偿的故障边界。 */
class NoteInteractionReliabilityTest {
    private JdbcTemplate jdbc;
    private NoteInteractionMapper realMapper;
    private NoteInteractionMapper mapper;
    private CountRpcService countRpc;
    private NoteInteractionConsumer consumer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 创建隔离数据库，并使用真实事务代理调用业务服务；故障只注入指定的 SQL 或 RPC。 */
    @BeforeEach
    void setup() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE t_note(id BIGINT PRIMARY KEY,creator_id BIGINT,status INT,visible INT)");
        jdbc.execute("CREATE TABLE t_note_like(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,note_id BIGINT,status INT,create_time TIMESTAMP,UNIQUE(user_id,note_id))");
        jdbc.execute("CREATE TABLE t_note_collection(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,note_id BIGINT,status INT,create_time TIMESTAMP,UNIQUE(user_id,note_id))");
        jdbc.execute("CREATE TABLE t_note_interaction_result(user_id BIGINT,request_id VARCHAR(36),note_id BIGINT,action_type VARCHAR(16),target_state TINYINT,author_id BIGINT,result_type VARCHAR(16),reason_code VARCHAR(32),count_generation BIGINT,actual_delta SMALLINT,created_at TIMESTAMP,count_status TINYINT DEFAULT 0,count_retry_count INT DEFAULT 0,count_next_retry_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,count_last_error VARCHAR(64),PRIMARY KEY(user_id,request_id))");
        jdbc.execute("CREATE TABLE t_note_interaction_failure(message_key CHAR(64) PRIMARY KEY,topic VARCHAR(255),broker_message_id VARCHAR(128),queue_id INT,queue_offset BIGINT,body BLOB,reason_code VARCHAR(32),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO t_note VALUES (10,20,1,0)");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setMapperLocations(new ClassPathResource("mapper/NoteDOMapper.xml"),
                new ClassPathResource("mapper/NoteLikeDOMapper.xml"),
                new ClassPathResource("mapper/NoteCollectionDOMapper.xml"),
                new ClassPathResource("mapper/NoteInteractionMapper.xml"));
        realMapper = new SqlSessionTemplate(factory.getObject()).getMapper(NoteInteractionMapper.class);
        mapper = mock(NoteInteractionMapper.class, AdditionalAnswers.delegatesTo(realMapper));
        JdbcTemplate generationJdbc = mock(JdbcTemplate.class);
        when(generationJdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(1L);
        NoteInteractionService target = new NoteInteractionService();
        ReflectionTestUtils.setField(target, "countJdbcTemplate", generationJdbc);
        ReflectionTestUtils.setField(target, "interactionMapper", mapper);
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        consumer = new NoteInteractionConsumer();
        ReflectionTestUtils.setField(consumer, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(consumer, "interactionService", proxyFactory.getProxy());
        countRpc = mock(CountRpcService.class);
    }

    /** 四类有效操作先提交并确认 MQ；计数服务不可用不回滚事实，恢复后可由新任务实例补偿。 */
    @Test
    void committedCommandsAreAcknowledgedAndCountsRecoverAfterRpcFailure() throws Exception {
        List<MessageExt> messages = List.of(command("LIKE", true), command("LIKE", false),
                command("COLLECT", true), command("COLLECT", false));
        doThrow(new IllegalStateException("计数服务暂不可用")).doNothing()
                .when(countRpc).applyInteractionResults(anyList());
        assertEquals(ConsumeOrderlyStatus.SUCCESS, consumer.consumeMessage(messages, null));
        verifyNoInteractions(countRpc);
        assertEquals(2, jdbc.queryForObject("SELECT status FROM t_note_like", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT status FROM t_note_collection", Integer.class));
        assertEquals(4, pendingCount());
        newCompensator().scan();
        assertEquals(4, pendingCount());
        assertEquals(4, jdbc.queryForObject("SELECT SUM(count_retry_count) FROM t_note_interaction_result", Integer.class));
        assertTrue(realMapper.findPendingCounts(100).isEmpty());
        makeRetryDue();
        newCompensator().scan();
        assertEquals(0, pendingCount());
        verify(countRpc, times(2)).applyInteractionResults(anyList());
    }

    /** 同一个 requestId 重投不会新增事实或任务，也不会重新打开已完成任务。 */
    @Test
    void repeatedRequestDoesNotReopenCompletedTask() throws Exception {
        MessageExt add = command("LIKE", true);
        assertEquals(ConsumeOrderlyStatus.SUCCESS, consumer.consumeMessage(List.of(add, add), null));
        assertEquals(1, pendingCount());
        newCompensator().scan();
        assertEquals(ConsumeOrderlyStatus.SUCCESS, consumer.consumeMessage(List.of(add), null));
        newCompensator().scan();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_note_like", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_note_interaction_result", Integer.class));
        assertEquals(0, pendingCount());
        verify(countRpc, times(1)).applyInteractionResults(anyList());
    }

    /** 非法 JSON、非法参数和 null 原文被可靠留存，前后有效命令仍按顺序执行。 */
    @Test
    void invalidMessagesAreStoredWithoutBlockingValidCommands() throws Exception {
        MessageExt malformed = message("broken-json");
        MessageExt invalid = message("{}");
        MessageExt nullCommand = message("null");
        List<MessageExt> batch = List.of(command("LIKE", true), malformed, invalid, nullCommand, command("LIKE", false));
        assertEquals(ConsumeOrderlyStatus.SUCCESS, consumer.consumeMessage(batch, null));
        assertEquals(2, jdbc.queryForObject("SELECT status FROM t_note_like", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM t_note_interaction_failure", Integer.class));
        assertArrayEquals(malformed.getBody(), jdbc.queryForObject(
                "SELECT body FROM t_note_interaction_failure WHERE reason_code='INVALID_JSON'", byte[].class));
        assertEquals(ConsumeOrderlyStatus.SUCCESS, consumer.consumeMessage(batch, null));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM t_note_interaction_failure", Integer.class));
        assertEquals(2, pendingCount());
    }

    /** 异常消息无法持久化时不允许确认整批，恢复后可原样重投。 */
    @Test
    void failureStorageUnavailableKeepsEntireBatchForRetry() throws Exception {
        List<MessageExt> batch = List.of(command("LIKE", true), message("bad"));
        doThrow(new DataAccessResourceFailureException("数据库不可用")).when(mapper).saveFailures(anyList());
        assertEquals(ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT, consumer.consumeMessage(batch, null));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_note_like", Integer.class));
        doAnswer(invocation -> { realMapper.saveFailures(invocation.getArgument(0)); return null; })
                .when(mapper).saveFailures(anyList());
        assertEquals(ConsumeOrderlyStatus.SUCCESS, consumer.consumeMessage(batch, null));
        assertEquals(1, pendingCount());
    }

    /** 事实写入后结果 SQL 失败时，事实、待处理任务和异常消息必须一起回滚。 */
    @Test
    void resultWriteFailureRollsBackFactsAndQuarantine() throws Exception {
        List<MessageExt> batch = List.of(command("COLLECT", true), message("bad"));
        doThrow(new DataAccessResourceFailureException("结果写入失败")).when(mapper).saveResults(anyList());
        assertEquals(ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT, consumer.consumeMessage(batch, null));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_note_collection", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_note_interaction_failure", Integer.class));
        assertEquals(0, pendingCount());
    }

    /** RPC 成功但完成标记写入失败时，再次补偿必须复用相同事件内容，供 Redis 幂等去重。 */
    @Test
    void completionWriteFailureReplaysTheSameImmutableResult() throws Exception {
        consumer.consumeMessage(List.of(command("LIKE", true)), null);
        doThrow(new DataAccessResourceFailureException("完成标记未保存"))
                .doAnswer(AdditionalAnswers.delegatesTo(realMapper)).when(mapper).completeCounts(anyList());
        newCompensator().scan();
        assertEquals(1, pendingCount());
        makeRetryDue();
        newCompensator().scan();
        assertEquals(0, pendingCount());
        ArgumentCaptor<List<NoteInteractionResultDO>> batches = ArgumentCaptor.forClass(List.class);
        verify(countRpc, times(2)).applyInteractionResults(batches.capture());
        assertEquals(batches.getAllValues().get(0), batches.getAllValues().get(1));
        // 已完成任务不能被晚到的失败结果重新置为待处理。
        realMapper.retryCounts(batches.getValue());
        assertEquals(0, pendingCount());
    }

    /** 连失败重试时间都写不回时，原待处理任务仍保留，下一次扫描继续处理。 */
    @Test
    void retryMetadataFailureDoesNotLoseTask() throws Exception {
        consumer.consumeMessage(List.of(command("LIKE", true)), null);
        doThrow(new IllegalStateException("RPC 超时")).doNothing().when(countRpc).applyInteractionResults(anyList());
        doThrow(new DataAccessResourceFailureException("重试状态保存失败")).when(mapper).retryCounts(anyList());
        newCompensator().scan();
        assertEquals(1, pendingCount());
        newCompensator().scan();
        assertEquals(0, pendingCount());
    }

    /** 只有非法消息的批次也可落库并确认，不执行空的 IN 查询。 */
    @Test
    void malformedOnlyBatchCanBeAcknowledged() {
        assertEquals(ConsumeOrderlyStatus.SUCCESS, consumer.consumeMessage(List.of(message("bad")), null));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_note_interaction_failure", Integer.class));
        assertEquals(0, pendingCount());
    }

    /** 无变化和业务拒绝直接标记完成，不调用计数服务。 */
    @Test
    void noChangeAndRejectedResultsDoNotCreatePendingCounts() throws Exception {
        consumer.consumeMessage(List.of(command("LIKE", false)), null);
        jdbc.update("UPDATE t_note SET status=2 WHERE id=10");
        consumer.consumeMessage(List.of(command("COLLECT", true)), null);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM t_note_interaction_result WHERE count_status=2", Integer.class));
        newCompensator().scan();
        verifyNoInteractions(countRpc);
    }

    /** 构造带独立 requestId 的有效命令。 */
    private MessageExt command(String action, boolean state) throws Exception {
        NoteInteractionCommandDTO command = new NoteInteractionCommandDTO(UUID.randomUUID().toString(), 30L, 10L, action, state);
        return message(objectMapper.writeValueAsString(command));
    }

    /** 构造稳定消息来源；重投复用同一对象和消息 ID。 */
    private MessageExt message(String body) {
        MessageExt message = new MessageExt();
        message.setTopic(NoteInteractionCommandDTO.TOPIC);
        message.setMsgId(UUID.randomUUID().toString());
        message.setQueueId(0);
        message.setQueueOffset(1);
        message.setBody(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }

    /** 每次创建新实例，验证补偿不依赖进程内状态。 */
    private NoteInteractionCountCompensator newCompensator() {
        NoteInteractionCountCompensator compensator = new NoteInteractionCountCompensator();
        ReflectionTestUtils.setField(compensator, "interactionMapper", mapper);
        ReflectionTestUtils.setField(compensator, "countRpcService", countRpc);
        return compensator;
    }

    /** 查询尚未完成的持久化计数任务数。 */
    private int pendingCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_note_interaction_result WHERE count_status=0", Integer.class);
    }

    /** 模拟重试时间到期，避免测试依赖真实等待。 */
    private void makeRetryDue() {
        jdbc.update("UPDATE t_note_interaction_result SET count_next_retry_time='2020-01-01 00:00:00' WHERE count_status=0");
    }
}
