package com.quanxiaoha.xiaohashu.note.biz;

import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteInteractionMapper;
import com.quanxiaoha.xiaohashu.note.biz.model.dto.NoteInteractionCommandDTO;
import com.quanxiaoha.xiaohashu.note.biz.service.impl.NoteInteractionService;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NoteInteractionDatabaseTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private NoteInteractionMapper mapper;
    private com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteDOMapper noteMapper;
    private NoteInteractionService service;

    @BeforeEach
    void setup() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.execute("CREATE TABLE t_note(id BIGINT PRIMARY KEY,creator_id BIGINT,status INT,visible INT,update_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE t_note_like(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,note_id BIGINT,status INT,create_time TIMESTAMP,UNIQUE(user_id,note_id))");
        jdbc.execute("CREATE TABLE t_note_collection(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,note_id BIGINT,status INT,create_time TIMESTAMP,UNIQUE(user_id,note_id))");
        jdbc.execute("CREATE TABLE t_note_interaction_result(user_id BIGINT,request_id VARCHAR(36),note_id BIGINT,action_type VARCHAR(16),target_state TINYINT,author_id BIGINT,result_type VARCHAR(16),reason_code VARCHAR(32),count_generation BIGINT,actual_delta SMALLINT,created_at TIMESTAMP,count_status TINYINT DEFAULT 0,count_retry_count INT DEFAULT 0,count_next_retry_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,count_last_error VARCHAR(64),PRIMARY KEY(user_id,request_id))");
        jdbc.execute("CREATE TABLE t_note_interaction_failure(message_key CHAR(64) PRIMARY KEY,topic VARCHAR(255),broker_message_id VARCHAR(128),queue_id INT,queue_offset BIGINT,body BLOB,reason_code VARCHAR(32),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO t_note(id,creator_id,status,visible) VALUES (10,20,1,0)");
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setMapperLocations(new ClassPathResource("mapper/NoteDOMapper.xml"),
                new ClassPathResource("mapper/NoteLikeDOMapper.xml"),
                new ClassPathResource("mapper/NoteCollectionDOMapper.xml"),
                new ClassPathResource("mapper/NoteInteractionMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(NoteInteractionMapper.class);
        noteMapper = new SqlSessionTemplate(factory.getObject()).getMapper(com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteDOMapper.class);
        service = new NoteInteractionService();
        var generationJdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        org.mockito.Mockito.when(generationJdbc.queryForObject(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(Long.class))).thenReturn(1L);
        ReflectionTestUtils.setField(service, "countJdbcTemplate", generationJdbc);
        ReflectionTestUtils.setField(service, "interactionMapper", mapper);
    }

    private NoteInteractionCommandDTO command(boolean target) {
        return new NoteInteractionCommandDTO(UUID.randomUUID().toString(), 30L, 10L, "LIKE", target);
    }

    @Test
    void realMapperWritesBatchAndReplayLoadsOriginalResult() {
        var add = command(true);
        var remove = command(false);
        var addAgain = command(true);
        var first = transaction.execute(s -> service.execute(List.of(add, remove, addAgain)));
        assertEquals(List.of(1, -1, 1), first.stream().map(r -> r.getActualDelta()).toList());
        assertEquals(1, jdbc.queryForObject("SELECT status FROM t_note_like", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM t_note_interaction_result", Integer.class));
        jdbc.update("UPDATE t_note SET status=2");
        var replay = transaction.execute(s -> service.execute(List.of(add)));
        assertEquals(1, replay.get(0).getActualDelta());
        assertEquals(20L, replay.get(0).getAuthorId());
        assertEquals("CHANGED", mapper.findResult(30, add.getRequestId()).getResultType());
    }

    @Test
    void factAndResultRollbackTogether() {
        var command = command(true);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(s -> {
            service.execute(List.of(command));
            throw new IllegalStateException("模拟事务提交前失败");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_note_like", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_note_interaction_result", Integer.class));
        transaction.executeWithoutResult(s -> service.execute(List.of(command)));
        assertEquals(1, mapper.countLikes(10));
    }

    @Test
    void deletionSnapshotIncludesCommittedFactsAndRejectsLaterCommands() {
        transaction.executeWithoutResult(s -> service.execute(List.of(command(true))));
        transaction.executeWithoutResult(s -> {
            assertEquals(1, mapper.countLikes(10));
            jdbc.update("UPDATE t_note SET status=2 WHERE id=10");
        });
        var rejected = transaction.execute(s -> service.execute(List.of(command(false)))).get(0);
        assertEquals("REJECTED", rejected.getResultType());
        assertEquals(1, mapper.countLikes(10));
    }

    /** 重建后旧请求复用原代次，新命令在实际执行时取得新代次。 */
    @Test
    void generationIsPersistedWithFactAndReusedOnReplay() {
        var add = command(true);
        var first = transaction.execute(status -> service.execute(List.of(add))).get(0);
        assertEquals(1L, first.getCountGeneration());
        var generationJdbc = (JdbcTemplate) ReflectionTestUtils.getField(service, "countJdbcTemplate");
        org.mockito.Mockito.when(generationJdbc.queryForObject(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(Long.class))).thenReturn(2L);
        var replay = transaction.execute(status -> service.execute(List.of(add))).get(0);
        assertEquals(1L, replay.getCountGeneration());
        var remove = transaction.execute(status -> service.execute(List.of(command(false)))).get(0);
        assertEquals(2L, remove.getCountGeneration());
        assertEquals(-1, remove.getActualDelta());
    }

    /** 删除事务持有笔记排他锁时，互动可读取已提交状态并完成，不等待该锁。 */
    @Test
    @Timeout(10)
    void interactionCanCommitWhileDeletionHoldsNoteLock() throws Exception {
        try (java.sql.Connection deletionConnection = jdbc.getDataSource().getConnection()) {
            deletionConnection.setAutoCommit(false);
            try (java.sql.Statement deletionStatement = deletionConnection.createStatement()) {
                deletionStatement.executeUpdate("UPDATE t_note SET status=2 WHERE id=10");
                var result = transaction.execute(status -> service.execute(List.of(command(true))));
                assertEquals("CHANGED", result.get(0).getResultType());
                assertEquals(1, result.get(0).getActualDelta());
                deletionConnection.commit();
            } finally {
                deletionConnection.rollback();
            }
        }
        // 接受删除和互动同时成功；后续的新请求仍按已删除状态拒绝。
        assertEquals(1, mapper.countLikes(10));
        var rejected = transaction.execute(status -> service.execute(List.of(command(false)))).get(0);
        assertEquals("REJECTED", rejected.getResultType());
    }

    /** 条件删除只允许作者迁移一次状态，重复请求及错误的前置状态不产生更新。 */
    @Test
    void conditionalDeletionOnlyUpdatesOnce() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        assertEquals(0, noteMapper.markDeletedIfStatus(10L, 99L, 1, now));
        assertEquals(0, noteMapper.markDeletedIfStatus(10L, 20L, 0, now));
        assertEquals(1, noteMapper.markDeletedIfStatus(10L, 20L, 1, now));
        assertEquals(0, noteMapper.markDeletedIfStatus(10L, 20L, 1, now));
        assertEquals(0, noteMapper.markDeletedIfStatus(10L, 20L, 2, now));
        assertEquals(2, mapper.findNote(10).getStatus());
    }

    /** 后续事务操作失败时，条件删除也回滚，原请求仍能重新完成删除。 */
    @Test
    void conditionalDeletionRollsBackWithTransaction() {
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            assertEquals(1, noteMapper.markDeletedIfStatus(10L, 20L, 1, java.time.LocalDateTime.now()));
            throw new IllegalStateException("模拟删除事件写入失败");
        }));
        assertEquals(1, mapper.findNote(10).getStatus());
        assertEquals(1, noteMapper.markDeletedIfStatus(10L, 20L, 1, java.time.LocalDateTime.now()));
    }
}
