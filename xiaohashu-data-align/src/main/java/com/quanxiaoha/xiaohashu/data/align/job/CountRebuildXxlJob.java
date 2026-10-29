package com.quanxiaoha.xiaohashu.data.align.job;

import com.quanxiaoha.framework.common.util.CountGenerationUtils;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** 手动触发的停写重建任务，仅适用于当前关系、笔记、计数共用事实库的部署。 */
@Component
public class CountRebuildXxlJob {
    @Resource
    private JdbcTemplate jdbcTemplate;
    @Resource
    private TransactionTemplate transactionTemplate;
    @Resource
    private StringRedisTemplate redisTemplate;
    private static final int BATCH_SIZE = 200;
    private static final long MAX_SAFE_INTEGER = 9007199254740991L;

    /** XXL-JOB 参数为唯一 rebuildId；失败后必须用同一参数重试，禁止设置周期调度。 */
    @XxlJob("countRebuildJobHandler")
    public void execute() {
        rebuild(XxlJobHelper.getJobParam());
    }

    /** 先持久化暂停状态，再加载独立键空间；失败保留暂停状态供同一任务恢复。 */
    public void rebuild(String rebuildId) {
        if (rebuildId == null || !rebuildId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("重建任务 ID 必须为 1 到 64 位字母、数字、下划线或横线");
        }
        transactionTemplate.executeWithoutResult(status -> prepare(rebuildId));
        transactionTemplate.executeWithoutResult(status -> loadAndActivate(rebuildId));
    }

    /** 排他锁等待所有正在执行的事实事务和计数调用结束，再提交持久化暂停标记。 */
    private void prepare(String rebuildId) {
        Map<String, Object> state = lockState();
        List<Map<String, Object>> tasks = jdbcTemplate.queryForList(
                "SELECT status FROM t_count_rebuild WHERE rebuild_id = ?", rebuildId);
        if (!tasks.isEmpty() && "COMPLETED".equals(tasks.get(0).get("status"))) {
            return;
        }
        if (!"READY".equals(state.get("status"))) {
            if (!rebuildId.equals(state.get("rebuild_id"))) {
                throw new IllegalStateException("已有未完成重建，请使用原 rebuildId 恢复");
            }
            return;
        }
        long previousGeneration = ((Number) state.get("generation")).longValue();
        long nextGeneration = Math.addExact(previousGeneration, 1L);
        jdbcTemplate.update("INSERT INTO t_count_rebuild(rebuild_id,previous_generation,next_generation,status) VALUES(?,?,?,'PREPARED')",
                rebuildId, previousGeneration, nextGeneration);
        jdbcTemplate.update("UPDATE t_count_generation SET status='REBUILDING',rebuild_id=?,next_generation=? WHERE id=1",
                rebuildId, nextGeneration);
    }

    /** 同一排他锁保护加载和切换；同任务重复执行覆盖绝对值，不重复累加。 */
    private void loadAndActivate(String rebuildId) {
        Map<String, Object> state = lockState();
        String taskStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM t_count_rebuild WHERE rebuild_id=?", String.class, rebuildId);
        if ("COMPLETED".equals(taskStatus)) {
            return;
        }
        if (!"REBUILDING".equals(state.get("status")) || !rebuildId.equals(state.get("rebuild_id"))) {
            throw new IllegalStateException("重建任务与暂停状态不一致");
        }
        long generation = ((Number) state.get("next_generation")).longValue();
        jdbcTemplate.update("UPDATE t_count_rebuild SET status='LOADING' WHERE rebuild_id=?", rebuildId);
        loadUsers(generation);
        loadNotes(generation);
        // 仅在全量快照写入成功后开放。旧代次事件将被计数服务跳过。
        jdbcTemplate.update("UPDATE t_count_generation SET generation=?,status='READY',next_generation=NULL WHERE id=1", generation);
        jdbcTemplate.update("UPDATE t_count_rebuild SET status='COMPLETED',completed_at=NOW(3) WHERE rebuild_id=?", rebuildId);
    }

    /** 获取单行排他锁；服务端共享锁与此锁共同构成恢复屏障。 */
    private Map<String, Object> lockState() {
        return jdbcTemplate.queryForMap("SELECT * FROM t_count_generation WHERE id=1 FOR UPDATE");
    }

    /** 从有效关注主表和有效作品互动统计用户五项计数，包含零计数用户。 */
    private void loadUsers(long generation) {
        long lastId = 0;
        while (true) {
            List<Long> userIds = jdbcTemplate.queryForList(
                    "SELECT id FROM t_user WHERE id>? ORDER BY id LIMIT ?", Long.class, lastId, BATCH_SIZE);
            if (userIds.isEmpty()) {
                return;
            }
            for (Long userId : userIds) {
                long following = count("SELECT COUNT(*) FROM t_following WHERE from_user_id=? AND type=1", userId);
                long fans = count("SELECT COUNT(*) FROM t_following WHERE to_user_id=? AND type=1", userId);
                long likes = count("SELECT COUNT(*) FROM t_note_like i JOIN t_note n ON n.id=i.note_id WHERE n.creator_id=? AND n.status=1 AND i.status=1", userId);
                long collections = count("SELECT COUNT(*) FROM t_note_collection i JOIN t_note n ON n.id=i.note_id WHERE n.creator_id=? AND n.status=1 AND i.status=1", userId);
                long works = count("SELECT COUNT(*) FROM t_note WHERE creator_id=? AND status=1", userId);
                writeSnapshot(generation, "user", userId, following, fans, likes, collections, works);
                lastId = userId;
            }
        }
    }

    /** 作品内部计数保留已删除作品的有效互动，与正常消费的口径保持一致。 */
    private void loadNotes(long generation) {
        long lastId = 0;
        while (true) {
            List<Long> noteIds = jdbcTemplate.queryForList(
                    "SELECT id FROM t_note WHERE id>? ORDER BY id LIMIT ?", Long.class, lastId, BATCH_SIZE);
            if (noteIds.isEmpty()) {
                return;
            }
            for (Long noteId : noteIds) {
                long likes = count("SELECT COUNT(*) FROM t_note_like WHERE note_id=? AND status=1", noteId);
                long collections = count("SELECT COUNT(*) FROM t_note_collection WHERE note_id=? AND status=1", noteId);
                writeSnapshot(generation, "note", noteId, likes, collections);
                lastId = noteId;
            }
        }
    }

    /** 查询单项事实数量，拒绝空结果。 */
    private long count(String sql, long targetId) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, targetId);
        if (value == null) {
            throw new IllegalStateException("事实统计结果为空");
        }
        return value;
    }

    /** 按线上固定字段布局写入绝对值；新代次不复制旧 seen，旧事件由代次隔离。 */
    private void writeSnapshot(long generation, String type, long id, long... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Long.BYTES);
        for (long value : values) {
            if (value < 0 || value > MAX_SAFE_INTEGER) {
                throw new IllegalStateException("事实计数超出允许范围");
            }
            buffer.putLong(value);
        }
        byte[] key = (CountGenerationUtils.targetKey(generation, type, id) + ":data").getBytes(StandardCharsets.UTF_8);
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.stringCommands().set(key, buffer.array());
            return null;
        });
    }
}
