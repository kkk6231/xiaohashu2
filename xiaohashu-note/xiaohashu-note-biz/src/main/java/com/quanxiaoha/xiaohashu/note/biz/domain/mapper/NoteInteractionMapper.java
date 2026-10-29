package com.quanxiaoha.xiaohashu.note.biz.domain.mapper;

import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.*;
import com.quanxiaoha.xiaohashu.note.biz.model.dto.NoteInteractionCommandDTO;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface NoteInteractionMapper {
    /** 互动时普通读取笔记状态，不与删除事务互斥；允许删除并发下的计数偏差。 */
    NoteDO findNote(@Param("noteId") long noteId);
    List<NoteInteractionResultDO> findResults(@Param("commands") List<NoteInteractionCommandDTO> commands);
    NoteInteractionResultDO findResult(@Param("userId") long userId, @Param("requestId") String requestId);
    List<NoteLikeDO> findLikes(@Param("commands") List<NoteInteractionCommandDTO> commands);
    List<NoteCollectionDO> findCollections(@Param("commands") List<NoteInteractionCommandDTO> commands);
    void saveResults(@Param("results") List<NoteInteractionResultDO> results);
    void saveLikes(@Param("rows") List<NoteLikeDO> rows);
    void saveCollections(@Param("rows") List<NoteCollectionDO> rows);
    long countLikes(@Param("noteId") long noteId);
    long countCollections(@Param("noteId") long noteId);

    /** 保存非法消息原文，相同消息重投时不重复插入。 */
    void saveFailures(@Param("failures") List<NoteInteractionFailureDO> failures);

    /** 批量读取到期的计数任务；无需抢占，重复处理仍使用同一计数事件键。 */
    List<NoteInteractionResultDO> findPendingCounts(@Param("limit") int limit);

    /** RPC 确认成功后标记完成；进程中断或此更新失败时仍会再次补偿。 */
    int completeCounts(@Param("results") List<NoteInteractionResultDO> results);

    /** 记录失败并推迟重试，不覆盖已被其他实例标记完成的任务。 */
    int retryCounts(@Param("results") List<NoteInteractionResultDO> results);
}
