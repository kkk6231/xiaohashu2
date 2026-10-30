package com.quanxiaoha.xiaohashu.note.biz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanxiaoha.xiaohashu.count.api.event.NoteInteractionEvent;
import com.quanxiaoha.xiaohashu.note.biz.consumer.NoteInteractionFactConsumer;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteLikeDOMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class NoteInteractionFactDatabaseTest {
    /** 重复点赞只产生一条事实记录，取消操作重复执行保持无效状态。 */
    @Test
    void repeatedMessagesKeepOneRecordAndTargetState() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE t_note_like (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT, note_id BIGINT, status INT, create_time TIMESTAMP, UNIQUE(user_id, note_id))");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setMapperLocations(new ClassPathResource("mapper/NoteLikeDOMapper.xml"));
        NoteLikeDOMapper mapper = new SqlSessionTemplate(factory.getObject()).getMapper(NoteLikeDOMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        NoteInteractionFactConsumer consumer = new NoteInteractionFactConsumer();
        ReflectionTestUtils.setField(consumer, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(consumer, "likeMapper", mapper);
        NoteInteractionEvent event = new NoteInteractionEvent();
        event.setEventId("event-1"); event.setUserId(1L); event.setNoteId(2L); event.setAuthorId(3L);
        event.setActionType("LIKE"); event.setTargetState(true);
        String like = objectMapper.writeValueAsString(event);
        consumer.onMessage(like);
        consumer.onMessage(like);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_note_like", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT status FROM t_note_like", Integer.class));
        event.setEventId("event-2"); event.setTargetState(false);
        String unlike = objectMapper.writeValueAsString(event);
        consumer.onMessage(unlike);
        consumer.onMessage(unlike);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_note_like", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT status FROM t_note_like", Integer.class));
    }
}
