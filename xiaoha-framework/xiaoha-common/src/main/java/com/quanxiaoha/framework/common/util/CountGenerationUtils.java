package com.quanxiaoha.framework.common.util;

import org.springframework.jdbc.core.JdbcTemplate;

/** 共库部署的计数恢复屏障。调用方必须在事务中持有共享锁直到业务完成。 */
public final class CountGenerationUtils {
    /** 工具类不允许实例化。 */
    private CountGenerationUtils() { }

    /** 重建持有排他锁；非 READY 状态拒绝请求，不把丢失数据当成零值。 */
    public static long lockCurrent(JdbcTemplate jdbcTemplate) {
        Long generation = jdbcTemplate.queryForObject(
                "SELECT generation FROM t_count_generation WHERE id = 1 AND status = 'READY' LOCK IN SHARE MODE",
                Long.class);
        if (generation == null || generation < 1) {
            throw new IllegalStateException("计数正在恢复，暂不可用");
        }
        return generation;
    }

    /** 第一代沿用旧键名，迁移不丢已有计数；新代次使用独立键空间。 */
    public static String targetKey(long generation, String type, long id) {
        String prefix = generation == 1 ? "counter:" : "counter:g" + generation + ":";
        return prefix + "{" + type + ":" + id + "}";
    }
}
