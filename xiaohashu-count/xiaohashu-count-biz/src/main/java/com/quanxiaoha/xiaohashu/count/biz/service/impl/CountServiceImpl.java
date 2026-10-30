package com.quanxiaoha.xiaohashu.count.biz.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanxiaoha.xiaohashu.count.api.dto.*;
import com.quanxiaoha.xiaohashu.count.biz.service.CountService;
import jakarta.annotation.Resource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@lombok.extern.slf4j.Slf4j
public class CountServiceImpl implements CountService {
    // Hash 的字段名就是读写协议；新增字段不需要调整其他字段的存储位置。
    private static final List<String> USER_FIELDS = List.of("followingTotal", "fansTotal",
            "likesReceivedTotal", "collectsReceivedTotal", "worksTotal");
    private static final List<String> NOTE_FIELDS = List.of("likeTotal", "collectTotal");
    private static final int MAX_CHANGE_BATCH_SIZE = 200;
    private static final int MAX_QUERY_BATCH_SIZE = 100;

    @Resource
    private StringRedisTemplate redisTemplate;
    @Resource
    private ObjectMapper objectMapper;

    private final DefaultRedisScript<Long> updateScript = new DefaultRedisScript<>();
    private final DefaultRedisScript<List> readScript = new DefaultRedisScript<>();

    /** 加载计数写入脚本。 */
    public CountServiceImpl() {
        updateScript.setLocation(new ClassPathResource("lua/count-apply.lua"));
        updateScript.setResultType(Long.class);
        readScript.setLocation(new ClassPathResource("lua/count-read.lua"));
        readScript.setResultType(List.class);
    }

    /**
     * 按用户或笔记分组写入。同一目标的去重、逐字段累加和写入由一次 Lua 完成。
     * 不同目标之间不构成事务：部分成功后由上游重试，已成功的目标通过事件键去重。
     */
    @Override
    public void apply(List<CountChangeDTO> changes) {
        Map<String, List<CountChangeDTO>> changesByTarget = validateAndGroupChanges(changes);
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
            if (change == null) {
                throw new IllegalArgumentException("计数变更不能为空");
            }
            List<String> fieldNames = fieldNamesFor(change.getTargetType());
            if (change.getTargetId() == null || change.getTargetId() <= 0
                    || change.getEventKey() == null || change.getEventKey().isBlank()
                    || change.getDeltas() == null || change.getDeltas().isEmpty()) {
                throw new IllegalArgumentException("计数变更参数不完整");
            }
            change.getDeltas().forEach((field, delta) -> {
                if (!fieldNames.contains(field) || delta == null) {
                    throw new IllegalArgumentException("计数字段或增量不合法");
                }
            });
            String targetKey = buildTargetKey(change.getTargetType(), change.getTargetId());
            changesByTarget.computeIfAbsent(targetKey, ignored -> new ArrayList<>()).add(change);
        }
        return changesByTarget;
    }

    /** 对一个目标原子执行去重及计数更新。 */
    private void applyTargetChanges(String targetKey, List<CountChangeDTO> targetChanges) {
        List<Map<String, Object>> scriptEvents = buildScriptEvents(targetChanges);
        try {
            redisTemplate.execute(updateScript, List.of(targetKey, targetKey + ":seen"),
                    objectMapper.writeValueAsString(scriptEvents));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("计数事件序列化失败", exception);
        }
    }

    /** 保留每个事件的身份，不能在 Java 中直接求和，否则 Lua 无法排除已处理的事件。 */
    private List<Map<String, Object>> buildScriptEvents(List<CountChangeDTO> targetChanges) {
        Map<String, Map<String, Long>> deltasByEvent = new LinkedHashMap<>();
        for (CountChangeDTO change : targetChanges) {
            Map<String, Long> previousDeltas = deltasByEvent.putIfAbsent(change.getEventKey(), change.getDeltas());
            if (previousDeltas != null && !previousDeltas.equals(change.getDeltas())) {
                throw new IllegalArgumentException("同一计数事件的内容不一致");
            }
        }
        List<Map<String, Object>> scriptEvents = new ArrayList<>();
        deltasByEvent.forEach((eventKey, deltas) -> {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("id", eventKey);
            event.put("deltas", deltas);
            scriptEvents.add(event);
        });
        return scriptEvents;
    }

    /** 查询用户五项计数。 */
    @Override
    public List<UserCountDTO> findUsers(List<Long> ids) {
        List<Long[]> counts = readCounts("user", ids);
        List<UserCountDTO> result = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            Long[] values = counts.get(i);
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
    public List<NoteCountDTO> findNotes(List<Long> ids) {
        List<Long[]> counts = readCounts("note", ids);
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

    /** 直接从 Redis 批量读取计数，不查询数据库。 */
    private List<Long[]> readCounts(String type, List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_QUERY_BATCH_SIZE
                || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("计数查询 ID 必须为正数，且数量在 1 到 100 之间");
        }
        List<String> fields = fieldNamesFor(type);
        // pipeline 批量发送单目标读取脚本；首次使用返回零，已处理记录存在但数据丢失返回 null。
        List<Object> results = redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Object execute(RedisOperations<K, V> operations) {
                // StringRedisTemplate 的 key/value 序列化器都是 String，这里的收窄是安全的。
                RedisOperations<String, String> stringOperations = (RedisOperations<String, String>) operations;
                for (Long id : ids) {
                    String targetKey = buildTargetKey(type, id);
                    stringOperations.execute(readScript, List.of(targetKey, targetKey + ":seen"), fields.toArray());
                }
                return null;
            }
        });
        List<Long[]> values = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            Long[] targetCounts = decode(results.get(i), fields.size());
            if (targetCounts[0] == null) {
                log.error("计数数据缺失，type: {}, id: {}", type, ids.get(i));
            }
            values.add(targetCounts);
        }
        return values;
    }

    /** 解码计数；数据丢失时返回空值供前端隐藏。 */
    @SuppressWarnings("unchecked")
    private static Long[] decode(Object raw, int fieldCount) {
        Long[] values = new Long[fieldCount];
        List<String> fields = (List<String>) raw;
        if (fields == null || "missing".equals(fields.get(0))) {
            return values;
        }
        for (int i = 0; i < fieldCount; i++) {
            try {
                values[i] = Math.max(0L, Long.parseLong(fields.get(i + 1)));
            } catch (NumberFormatException exception) {
                throw new IllegalStateException("计数数据格式错误", exception);
            }
        }
        return values;
    }

    /** 计数 Hash 和去重 Key 共用 hash tag，支持单目标 Lua 原子操作。 */
    private static String buildTargetKey(String targetType, long targetId) {
        return "counter:{" + targetType + ":" + targetId + "}";
    }

    /** 返回该对象允许写入的字段。 */
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
