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
    private NoteInteractionService service;

    @BeforeEach
    void setup() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.execute("CREATE TABLE t_note(id BIGINT PRIMARY KEY,creator_id BIGINT,status INT,visible INT)");
        jdbc.execute("CREATE TABLE t_note_like(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,note_id BIGINT,status INT,create_time TIMESTAMP,UNIQUE(user_id,note_id))");
        jdbc.execute("CREATE TABLE t_note_collection(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,note_id BIGINT,status INT,create_time TIMESTAMP,UNIQUE(user_id,note_id))");
        jdbc.execute("CREATE TABLE t_note_interaction_result(user_id BIGINT,request_id VARCHAR(36),note_id BIGINT,action_type VARCHAR(16),target_state TINYINT,author_id BIGINT,result_type VARCHAR(16),reason_code VARCHAR(32),actual_delta SMALLINT,created_at TIMESTAMP,PRIMARY KEY(user_id,request_id))");
        jdbc.update("INSERT INTO t_note VALUES (10,20,1,0)");
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setMapperLocations(new ClassPathResource("mapper/NoteDOMapper.xml"),
                new ClassPathResource("mapper/NoteLikeDOMapper.xml"),
                new ClassPathResource("mapper/NoteCollectionDOMapper.xml"),
                new ClassPathResource("mapper/NoteInteractionMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(NoteInteractionMapper.class);
        service = new NoteInteractionService();
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
            mapper.lockNote(10);
            assertEquals(1, mapper.countLikes(10));
            jdbc.update("UPDATE t_note SET status=2 WHERE id=10");
        });
        var rejected = transaction.execute(s -> service.execute(List.of(command(false)))).get(0);
        assertEquals("REJECTED", rejected.getResultType());
        assertEquals(1, mapper.countLikes(10));
    }
}
