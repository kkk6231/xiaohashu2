package com.quanxiaoha.xiaohashu.note.biz.service.impl;

import org.springframework.jdbc.core.JdbcTemplate;
import com.quanxiaoha.framework.common.util.CountGenerationUtils;

import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.*;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteInteractionMapper;
import com.quanxiaoha.xiaohashu.note.biz.model.dto.NoteInteractionCommandDTO;
import com.quanxiaoha.xiaohashu.note.biz.enums.NoteStatusEnum;
import com.quanxiaoha.xiaohashu.note.biz.enums.NoteVisibleEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class NoteInteractionService {
    @Resource
    private JdbcTemplate countJdbcTemplate;
    @Resource
    private NoteInteractionMapper interactionMapper;

    /** 事实和处理结果一起提交，RPC 重试时直接复用原结果。 */
    @Transactional(rollbackFor = Exception.class)
    public List<NoteInteractionResultDO> execute(List<NoteInteractionCommandDTO> commands) {
        long countGeneration = CountGenerationUtils.lockCurrent(countJdbcTemplate);
        for (NoteInteractionCommandDTO command : commands) {
            command.validate();
        }
        // 每篇笔记只普通读取一次，不让不同用户的互动竞争笔记排他锁。
        // 读取后笔记可能被删除，仍允许本次互动提交；删除并发下作者汇总计数可有偏差。
        Map<Long, NoteDO> notes = new HashMap<>();
        for (NoteInteractionCommandDTO command : commands) {
            Long noteId = command.getNoteId();
            if (!notes.containsKey(noteId)) {
                notes.put(noteId, interactionMapper.findNote(noteId));
            }
        }
        // 已提交的请求复用原始结果，包括原始增量；不能依据当前状态重新计算。
        Map<String, NoteInteractionResultDO> results = new LinkedHashMap<>();
        for (NoteInteractionResultDO row : interactionMapper.findResults(commands)) {
            results.put(row.getUserId() + ":" + row.getRequestId(), row);
        }
        // 批内后续命令读取前一条命令更新后的状态，保证“点赞→取消”连续计算。
        Map<String, Integer> states = new HashMap<>();
        interactionMapper.findLikes(commands).forEach(r ->
                states.put(stateKey(r.getUserId(), r.getNoteId(), "LIKE"), r.getStatus()));
        interactionMapper.findCollections(commands).forEach(r ->
                states.put(stateKey(r.getUserId(), r.getNoteId(), "COLLECT"), r.getStatus()));
        // 事实表只写每个用户/笔记的最终状态，结果表则保留每条请求的实际增量。
        Map<String, NoteLikeDO> likes = new LinkedHashMap<>();
        Map<String, NoteCollectionDO> collections = new LinkedHashMap<>();
        List<NoteInteractionResultDO> newResults = new ArrayList<>();
        List<NoteInteractionResultDO> batchResults = new ArrayList<>();
        for (NoteInteractionCommandDTO command : commands) {
            NoteInteractionResultDO existing = results.get(command.identity());
            if (existing != null) {
                // 冲突请求不执行，也不能把旧结果按新参数重新提交。
                if (Objects.equals(existing.getNoteId(), command.getNoteId())
                        && Objects.equals(existing.getActionType(), command.getActionType())
                        && Objects.equals(existing.getTargetState(), command.getTargetState())) {
                    batchResults.add(existing);
                }
                continue;
            }
            NoteInteractionResultDO result = new NoteInteractionResultDO();
            result.setCountGeneration(countGeneration);
            result.setUserId(command.getUserId());
            result.setRequestId(command.getRequestId());
            result.setNoteId(command.getNoteId());
            result.setActionType(command.getActionType());
            result.setTargetState(command.getTargetState());
            result.setCreatedAt(LocalDateTime.now());
            result.setActualDelta(0);
            NoteDO note = notes.get(command.getNoteId());
            if (note != null) {
                result.setAuthorId(note.getCreatorId());
            }
            if (note == null || !Objects.equals(note.getStatus(), NoteStatusEnum.NORMAL.getCode())) {
                result.setResultType("REJECTED");
                result.setReasonCode("NOTE_UNAVAILABLE");
            } else if (!Objects.equals(note.getCreatorId(), command.getUserId())
                    && !Objects.equals(note.getVisible(), NoteVisibleEnum.PUBLIC.getCode())) {
                result.setResultType("REJECTED");
                result.setReasonCode("NOTE_NOT_VISIBLE");
            } else {
                String key = stateKey(command.getUserId(), command.getNoteId(), command.getActionType());
                boolean currentlyActive = states.getOrDefault(key, 2) == 1;
                int delta = (command.getTargetState() ? 1 : 0) - (currentlyActive ? 1 : 0);
                result.setActualDelta(delta);
                result.setResultType(delta == 0 ? "NO_CHANGE" : "CHANGED");
                if (delta != 0) {
                    int status = command.getTargetState() ? 1 : 2;
                    states.put(key, status);
                    if ("LIKE".equals(command.getActionType())) {
                        likes.put(key, NoteLikeDO.builder().userId(command.getUserId()).noteId(command.getNoteId())
                                .status(status).createTime(result.getCreatedAt()).build());
                    } else {
                        collections.put(key, NoteCollectionDO.builder().userId(command.getUserId()).noteId(command.getNoteId())
                                .status(status).createTime(result.getCreatedAt()).build());
                    }
                }
            }
            results.put(command.identity(), result);
            newResults.add(result);
            batchResults.add(result);
        }
        // 事实和结果同事务提交。之后计数 RPC 失败时，下一次消费仍能拿到相同增量。
        if (!likes.isEmpty()) {
            interactionMapper.saveLikes(new ArrayList<>(likes.values()));
        }
        if (!collections.isEmpty()) {
            interactionMapper.saveCollections(new ArrayList<>(collections.values()));
        }
        if (!newResults.isEmpty()) {
            interactionMapper.saveResults(newResults);
        }
        return batchResults;
    }

    private String stateKey(long userId, long noteId, String action) {
        return userId + ":" + noteId + ":" + action;
    }
}
