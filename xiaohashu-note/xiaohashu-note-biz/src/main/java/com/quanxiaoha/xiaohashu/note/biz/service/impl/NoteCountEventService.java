package com.quanxiaoha.xiaohashu.note.biz.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanxiaoha.xiaohashu.count.api.event.NoteCountEvent;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.*;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.*;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NoteCountEventService {
    @Resource
    private NoteDOMapper noteDOMapper;
    @Resource
    private NoteEventOutboxMapper outboxMapper;
    @Resource
    private ObjectMapper objectMapper;

    /** 在删除事务中登记缓存失效任务，不在事务提交前访问缓存。 */
    @Transactional(rollbackFor = Exception.class)
    public void appendCacheInvalidation(long noteId) {
        NoteEventOutboxDO task = NoteEventOutboxDO.builder()
                .noteId(noteId)
                .eventType("CACHE_INVALIDATE")
                .payload("{}")
                .build();
        outboxMapper.insert(task);
    }

    /** 发布事实与计数事件同事务提交。 */
    @Transactional(rollbackFor = Exception.class)
    public void publish(NoteDO note) {
        noteDOMapper.insert(note);
        append(note, "NOTE_PUBLISHED", 0, 0);
    }

    /** 将事实事务的增量快照写入原 Outbox，重发时保持不变。 */
    @Transactional(rollbackFor = Exception.class)
    public void append(NoteDO note, String type, long likes, long collections) {
        NoteEventOutboxDO row = NoteEventOutboxDO.builder()
                .noteId(note.getId())
                .eventType(type)
                .payload("{}")
                .build();
        outboxMapper.insert(row);
        NoteCountEvent event = NoteCountEvent.builder()
                .eventId(row.getId())
                .noteId(note.getId())
                .authorId(note.getCreatorId())
                .actorId(note.getCreatorId())
                .occurredAt(java.time.LocalDateTime.now().toString())
                .eventType(type)
                .removedLikeTotal(likes)
                .removedCollectTotal(collections)
                .build();
        try {
            row.setPayload(objectMapper.writeValueAsString(event));
        } catch (Exception exception) {
            throw new IllegalStateException("作品计数事件序列化失败", exception);
        }
        outboxMapper.updatePayload(row);
    }
}
