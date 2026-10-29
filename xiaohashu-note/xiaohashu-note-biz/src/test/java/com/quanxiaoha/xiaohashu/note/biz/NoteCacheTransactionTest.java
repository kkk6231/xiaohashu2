package com.quanxiaoha.xiaohashu.note.biz;

import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteEventOutboxMapper;
import com.quanxiaoha.xiaohashu.note.biz.service.impl.NoteCountEventService;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class NoteCacheTransactionTest {
    /** 未提交失效任务对投递连接不可见；业务回滚时任务一起回滚。 */
    @Test
    void taskBecomesVisibleOnlyAfterBusinessCommit() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE t_note_event_outbox(id BIGINT AUTO_INCREMENT PRIMARY KEY,note_id BIGINT,event_type VARCHAR(32),payload VARCHAR(100))");
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setMapperLocations(new ClassPathResource("mapper/NoteEventOutboxMapper.xml"));
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(NoteEventOutboxMapper.class);
        var service = new NoteCountEventService();
        ReflectionTestUtils.setField(service, "outboxMapper", mapper);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            service.appendCacheInvalidation(10L);
            assertEquals(0, externalTaskCount(dataSource));
            throw new IllegalStateException("rollback delete");
        }));
        assertEquals(0, externalTaskCount(dataSource));
        transaction.executeWithoutResult(status -> {
            service.appendCacheInvalidation(10L);
            assertEquals(0, externalTaskCount(dataSource));
        });
        assertEquals(1, externalTaskCount(dataSource));
    }

    /** 独立连接模拟提交后轮询的 Outbox 投递器。 */
    private int externalTaskCount(DriverManagerDataSource dataSource) {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM t_note_event_outbox")) {
            rows.next();
            return rows.getInt(1);
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
