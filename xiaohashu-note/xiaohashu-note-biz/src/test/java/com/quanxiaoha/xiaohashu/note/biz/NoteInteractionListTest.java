package com.quanxiaoha.xiaohashu.note.biz;

import com.quanxiaoha.framework.biz.context.holder.LoginUserContextHolder;
import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.framework.common.response.PageResponse;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteLikeDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteCollectionDOMapper;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.FindInteractionNoteListReqVO;
import com.quanxiaoha.xiaohashu.note.biz.model.vo.FindInteractionNoteListRspVO;
import com.quanxiaoha.xiaohashu.note.biz.service.impl.NoteServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 用真实 Mapper SQL 验证点赞、收藏列表的分页、权限过滤及取消行为。 */
class NoteInteractionListTest {
    private JdbcTemplate jdbc;
    private NoteServiceImpl service;
    private org.springframework.data.redis.core.ZSetOperations zset;

    /** 创建隔离测试库，装载两份真实 Mapper。 */
    @BeforeEach
    void setup() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE t_note(id BIGINT PRIMARY KEY,creator_id BIGINT,status INT,visible INT,title VARCHAR(100),type INT,img_uris VARCHAR(100),video_uri VARCHAR(100))");
        for (String table : new String[]{"t_note_like", "t_note_collection"}) {
            jdbc.execute("CREATE TABLE " + table + "(id BIGINT PRIMARY KEY,user_id BIGINT,note_id BIGINT,status INT,create_time TIMESTAMP,UNIQUE(user_id,note_id))");
            for (int noteId = 1; noteId <= 7; noteId++) {
                jdbc.update("INSERT INTO " + table + " VALUES (?,10,?,1,'2026-10-29 10:00:00')", noteId, noteId);
            }
            jdbc.update("UPDATE " + table + " SET status=2 WHERE note_id=5");
            jdbc.update("UPDATE " + table + " SET user_id=99 WHERE note_id=6");
        }
        jdbc.update("INSERT INTO t_note VALUES (1,20,1,0,'public',1,'image',NULL),(2,10,1,1,'own-private',1,NULL,NULL),(3,20,1,1,'other-private',1,NULL,NULL),(4,20,2,0,'deleted',1,NULL,NULL),(5,20,1,0,'cancelled',1,NULL,NULL),(6,20,1,0,'other-user',1,NULL,NULL)");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setMapperLocations(new ClassPathResource("mapper/NoteLikeDOMapper.xml"),
                new ClassPathResource("mapper/NoteCollectionDOMapper.xml"),
                new ClassPathResource("mapper/NoteDOMapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        service = new NoteServiceImpl();
        org.springframework.data.redis.core.StringRedisTemplate redis = org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        zset = org.mockito.Mockito.mock(org.springframework.data.redis.core.ZSetOperations.class);
        org.mockito.Mockito.when(redis.opsForZSet()).thenReturn(zset);
        ReflectionTestUtils.setField(service, "interactionRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "noteDOMapper", session.getMapper(com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteDOMapper.class));
        ReflectionTestUtils.setField(service, "noteLikeDOMapper", session.getMapper(NoteLikeDOMapper.class));
        ReflectionTestUtils.setField(service, "noteCollectionDOMapper", session.getMapper(NoteCollectionDOMapper.class));
        LoginUserContextHolder.setUserId(10L);
    }

    /** 清理登录上下文，避免测试之间串用身份。 */
    @AfterEach
    void cleanup() {
        LoginUserContextHolder.remove();
    }

    /** 相同时间以 ID 排序；过滤私有、删除、取消、其他用户及不存在的笔记。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void pagesOnlyContainAccessibleActiveNotes(boolean liked) {
        FindInteractionNoteListReqVO request = new FindInteractionNoteListReqVO();
        request.setPageSize(1);
        PageResponse<FindInteractionNoteListRspVO> first = query(liked, request);
        assertEquals(2, first.getTotalCount());
        assertEquals(2, first.getTotalPage());
        assertEquals(2L, first.getData().get(0).getNoteId());
        request.setPageNo(2);
        PageResponse<FindInteractionNoteListRspVO> second = query(liked, request);
        assertEquals(1L, second.getData().get(0).getNoteId());
        assertEquals("image", second.getData().get(0).getImgUris());
        assertNotNull(second.getData().get(0).getInteractionTime());
        request.setPageNo(3);
        assertTrue(query(liked, request).getData().isEmpty());
        LoginUserContextHolder.setUserId(888L);
        assertEquals(0, query(liked, request).getTotalCount());
    }

    /** 缓存命中仍使用真实 SQL 过滤其他用户的私有笔记。 */
    @org.junit.jupiter.api.Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void cachedNotesAreFilteredByVisibility() {
        org.mockito.Mockito.when(zset.size(org.mockito.ArgumentMatchers.anyString())).thenReturn(3L);
        java.util.Set entries = new java.util.LinkedHashSet();
        entries.add(new org.springframework.data.redis.core.DefaultTypedTuple<>("3", 3000.0));
        entries.add(new org.springframework.data.redis.core.DefaultTypedTuple<>("2", 2000.0));
        entries.add(new org.springframework.data.redis.core.DefaultTypedTuple<>("1", 1000.0));
        org.mockito.Mockito.when(zset.reverseRangeWithScores(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(0L), org.mockito.ArgumentMatchers.eq(9L))).thenReturn(entries);
        FindInteractionNoteListReqVO request = new FindInteractionNoteListReqVO();
        var page = service.findLikedNotes(request);
        assertEquals(2, page.getData().size());
        assertEquals(2L, page.getData().get(0).getNoteId());
        assertEquals(1L, page.getData().get(1).getNoteId());
    }

    /** 取消后不再展示；重新激活并更新互动时间后排到前面。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cancellationAndReactivationChangeList(boolean liked) {
        String table = liked ? "t_note_like" : "t_note_collection";
        FindInteractionNoteListReqVO request = new FindInteractionNoteListReqVO();
        jdbc.update("UPDATE " + table + " SET status=2 WHERE note_id=1");
        assertEquals(1, query(liked, request).getTotalCount());
        jdbc.update("UPDATE " + table + " SET status=1,create_time='2026-10-29 11:00:00' WHERE note_id=1");
        assertEquals(1L, query(liked, request).getData().get(0).getNoteId());
    }

    /** Service 层也拒绝未登录、空参数和越界分页，防止绕过入口校验。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsInvalidRequestAndMissingLogin(boolean liked) {
        FindInteractionNoteListReqVO request = new FindInteractionNoteListReqVO();
        assertThrows(BizException.class, () -> query(liked, null));
        request.setPageSize(101);
        assertThrows(BizException.class, () -> query(liked, request));
        request.setPageSize(10);
        request.setPageNo(0);
        assertThrows(BizException.class, () -> query(liked, request));
        request.setPageNo(1);
        LoginUserContextHolder.remove();
        assertThrows(BizException.class, () -> query(liked, request));
    }

    /** 在同一组测试中覆盖点赞、收藏两个入口。 */
    private PageResponse<FindInteractionNoteListRspVO> query(boolean liked, FindInteractionNoteListReqVO request) {
        if (liked) {
            return service.findLikedNotes(request);
        }
        return service.findCollectedNotes(request);
    }
}
