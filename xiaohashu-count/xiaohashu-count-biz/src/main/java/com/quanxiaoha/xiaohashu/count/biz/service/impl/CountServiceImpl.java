package com.quanxiaoha.xiaohashu.count.biz.service.impl;

import org.springframework.jdbc.core.JdbcTemplate;
import com.quanxiaoha.framework.common.util.CountGenerationUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanxiaoha.xiaohashu.count.api.dto.*;
import com.quanxiaoha.xiaohashu.count.biz.service.CountService;
import jakarta.annotation.Resource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class CountServiceImpl implements CountService {
    @Resource
    private JdbcTemplate countJdbcTemplate;
    // 顺序即 Redis String 的字段布局，每个字段占 8 字节；读写必须保持一致。
    private static final List<String> USER_FIELDS = List.of("followingTotal", "fansTotal",
            "likesReceivedTotal", "collectsReceivedTotal", "worksTotal");
    private static final List<String> NOTE_FIELDS = List.of("likeTotal", "collectTotal");
    // Lua 使用双精度数值，限制在可精确表示的整数范围内。
    private static final long MAX_SAFE_INTEGER = 9007199254740991L;
    private static final int MAX_CHANGE_BATCH_SIZE = 200;
    private static final int MAX_QUERY_BATCH_SIZE = 100;

    @Resource
    private StringRedisTemplate redisTemplate;
    @Resource
    private ObjectMapper objectMapper;

    private final DefaultRedisScript<Long> updateScript = new DefaultRedisScript<>();
    private final DefaultRedisScript<String> readScript = new DefaultRedisScript<>();

    /** 加载计数读写脚本。 */
    public CountServiceImpl() {
        updateScript.setLocation(new ClassPathResource("lua/count-apply.lua"));
        updateScript.setResultType(Long.class);
        readScript.setLocation(new ClassPathResource("lua/count-read.lua"));
        readScript.setResultType(String.class);
    }

    /**
     * 按用户或笔记分组写入。同一目标的去重、增量聚合和写入由一次 Lua 完成。
     * 不同目标之间不构成事务：部分成功后由上游重试，已成功的目标通过事件键去重。
     */
    @Override
    @Transactional
    public void apply(List<CountChangeDTO> changes) {
        long generation = CountGenerationUtils.lockCurrent(countJdbcTemplate);
        List<CountChangeDTO> currentChanges = new ArrayList<>();
        if (changes == null || changes.isEmpty() || changes.size() > MAX_CHANGE_BATCH_SIZE) {
            throw new IllegalArgumentException("计数批量参数不合法");
        }
        for (CountChangeDTO change : changes) {
            if (change == null || change.getCountGeneration() == null || change.getCountGeneration() < 1
                    || change.getCountGeneration() > generation) {
                throw new IllegalArgumentException("计数代次缺失或尚未切换");
            }
            if (change.getCountGeneration() == generation) {
                currentChanges.add(change);
            }
        }
        if (currentChanges.isEmpty()) {
            return;
        }
        Map<String, List<CountChangeDTO>> changesByTarget = validateAndGroupChanges(currentChanges);
        for (Map.Entry<String, List<CountChangeDTO>> target : changesByTarget.entrySet()) {
            applyTargetChanges(target.getKey(), target.getValue());
        }
    }

    /** 先完成整批参数校验，再开始访问 Redis。 */
    private Map<String, List<CountChangeDTO>> validateAndGroupChanges(List<CountChangeDTO> changes) {
        if (changes == null || changes.isEmpty() || changes.size() > MAX_CHANGE_BATCH_SIZE) {
            throw new IllegalArgumentException("计数变更条数必须在 1 到 200 之间");
        }
        Map<String, List<CountChangeDTO>> changesByTarget = new LinkedHashMap<>();
        for (CountChangeDTO change : changes) {
            List<String> fieldNames = fieldNamesFor(change.getTargetType());
            if (change.getTargetId() == null || change.getTargetId() <= 0
                    || change.getEventKey() == null || change.getEventKey().isBlank()
                    || change.getDeltas() == null || change.getDeltas().isEmpty()) {
                throw new IllegalArgumentException("计数变更参数不完整");
            }
            change.getDeltas().forEach((field, delta) -> {
                if (!fieldNames.contains(field) || delta == null
                        || delta < -MAX_SAFE_INTEGER || delta > MAX_SAFE_INTEGER) {
                    throw new IllegalArgumentException("计数字段或增量不合法");
                }
            });
            String targetKey = CountGenerationUtils.targetKey(change.getCountGeneration(), change.getTargetType(), change.getTargetId());
            changesByTarget.computeIfAbsent(targetKey, ignored -> new ArrayList<>()).add(change);
        }
        return changesByTarget;
    }

    /** 对一个目标原子执行去重及计数更新。 */
    private void applyTargetChanges(String targetKey, List<CountChangeDTO> targetChanges) {
        List<String> fieldNames = fieldNamesFor(targetChanges.get(0).getTargetType());
        List<Map<String, Object>> scriptEvents = buildScriptEvents(targetChanges, fieldNames);
        try {
            redisTemplate.execute(updateScript, List.of(targetKey + ":data", targetKey + ":seen"),
                    String.valueOf(fieldNames.size()), objectMapper.writeValueAsString(scriptEvents));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("计数事件序列化失败", exception);
        }
    }

    /** 保留每个事件的身份，不能在 Java 中直接求和，否则 Lua 无法排除已处理的事件。 */
    private List<Map<String, Object>> buildScriptEvents(List<CountChangeDTO> targetChanges,
                                                       List<String> fieldNames) {
        Map<String, Map<String, Long>> deltasByEvent = new LinkedHashMap<>();
        for (CountChangeDTO change : targetChanges) {
            Map<String, Long> previousDeltas = deltasByEvent.putIfAbsent(change.getEventKey(), change.getDeltas());
            if (previousDeltas != null && !previousDeltas.equals(change.getDeltas())) {
                throw new IllegalArgumentException("同一计数事件的内容不一致");
            }
        }
        List<Map<String, Object>> scriptEvents = new ArrayList<>();
        deltasByEvent.forEach((eventKey, deltas) -> {
            // 按二进制字段布局补齐增量，未变更的字段传 0。
            List<Long> orderedDeltas = fieldNames.stream()
                    .map(field -> deltas.getOrDefault(field, 0L)).toList();
            scriptEvents.add(Map.of("id", eventKey, "values", orderedDeltas));
        });
        return scriptEvents;
    }

    /** 查询用户五项计数。 */
    @Override
    @Transactional
    public List<UserCountDTO> findUsers(List<Long> ids) {
        List<long[]> counts = readCounts("user", ids);
        List<UserCountDTO> result = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            long[] values = counts.get(i);
            UserCountDTO dto = new UserCountDTO();
            dto.setUserId(ids.get(i));
            dto.setFollowingTotal(values[0]);
            dto.setFansTotal(values[1]);
            dto.setLikesReceivedTotal(values[2]);
            dto.setCollectsReceivedTotal(values[3]);
            dto.setWorksTotal(values[4]);
            result.add(dto);
        }
        return result;
    }

    /** 查询作品点赞和收藏计数。 */
    @Override
    @Transactional
    public List<NoteCountDTO> findNotes(List<Long> ids) {
        List<long[]> counts = readCounts("note", ids);
        List<NoteCountDTO> result = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            NoteCountDTO dto = new NoteCountDTO();
            dto.setNoteId(ids.get(i));
            dto.setLikeTotal(counts.get(i)[0]);
            dto.setCollectTotal(counts.get(i)[1]);
            result.add(dto);
        }
        return result;
    }

    /** 在恢复屏障内批量读取当前代次的计数。 */
    private List<long[]> readCounts(String type, List<Long> ids) {
        long generation = CountGenerationUtils.lockCurrent(countJdbcTemplate);
        if (ids == null || ids.isEmpty() || ids.size() > MAX_QUERY_BATCH_SIZE || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("计数查询 ID 必须为正数，且数量在 1 到 100 之间");
        }
        byte[] script = readScript.getScriptAsString().getBytes(StandardCharsets.UTF_8);
        // 每个目标在 Lua 内原子读取；pipeline 仅批量发送，不承担一致性保证。
        // 直接 EVAL 避免 pipeline 中 EVALSHA 缓存未命中，并保留原始二进制返回值。
        List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Long id : ids) {
                String prefix = CountGenerationUtils.targetKey(generation, type, id);
                connection.scriptingCommands().eval(script, ReturnType.VALUE, 2,
                        (prefix + ":data").getBytes(StandardCharsets.UTF_8),
                        (prefix + ":seen").getBytes(StandardCharsets.UTF_8));
            }
            return null;
        }, org.springframework.data.redis.serializer.RedisSerializer.byteArray());
        List<long[]> values = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            byte[] raw = (byte[]) results.get(i);
            values.add(decode(raw, fieldNamesFor(type).size()));
        }
        return values;
    }

    /** 解码固定长度计数，展示时把临时负数截断为零。 */
    public static long[] decode(byte[] raw, int fieldCount) {
        long[] values = new long[fieldCount];
        if (raw == null) {
            return values;
        }
        if (raw.length != fieldCount * Long.BYTES) {
            throw new IllegalStateException("计数数据长度错误");
        }
        ByteBuffer buffer = ByteBuffer.wrap(raw);
        // 删除事件可能先到达，内部保留负数抵消迟到增量；展示时才截断为 0。
        for (int i = 0; i < fieldCount; i++) {
            values[i] = Math.max(0, buffer.getLong());
        }
        return values;
    }

    /** 返回与二进制布局一致的字段顺序。 */
    private static List<String> fieldNamesFor(String type) {
        if ("user".equals(type)) {
            return USER_FIELDS;
        }
        if ("note".equals(type)) {
            return NOTE_FIELDS;
        }
        throw new IllegalArgumentException("不支持的计数对象");
    }


}
