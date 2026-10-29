package com.quanxiaoha.xiaohashu.note.biz.domain.mapper;

import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.*;
import com.quanxiaoha.xiaohashu.note.biz.model.dto.NoteInteractionCommandDTO;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface NoteInteractionMapper {
    /** 互动时普通读取笔记状态，不与删除事务互斥；允许删除并发下的计数偏差。 */
    NoteDO findNote(@Param("noteId") long noteId);
    /**
     * 批量读取本批命令已提交的处理结果（FOR UPDATE）。
     * 这是幂等的第一层：命中就复用原结果，不再重新计算增量。
     */
    List<NoteInteractionResultDO> findResults(@Param("commands") List<NoteInteractionCommandDTO> commands);

    /**
     * 按幂等键查询单条处理结果，供“查询请求结果”接口使用；未处理时返回 null。
     */
    NoteInteractionResultDO findResult(@Param("userId") long userId, @Param("requestId") String requestId);

    /**
     * 批量读取本批涉及的点赞事实（FOR UPDATE）。
     * 行锁把“读状态 → 算增量 → 写状态”保护在同一个事务里。
     */
    List<NoteLikeDO> findLikes(@Param("commands") List<NoteInteractionCommandDTO> commands);

    /**
     * 批量读取本批涉及的收藏事实（FOR UPDATE），语义与点赞一致。
     */
    List<NoteCollectionDO> findCollections(@Param("commands") List<NoteInteractionCommandDTO> commands);

    /**
     * 批量写入处理结果，每条命令一行。
     * 净增量为 0 的命令直接写成“已完成”，不进入计数补偿。
     */
    void saveResults(@Param("results") List<NoteInteractionResultDO> results);

    /**
     * 按最终状态批量 upsert 点赞事实，同一用户同一篇笔记只会写一次。
     */
    void saveLikes(@Param("rows") List<NoteLikeDO> rows);

    /**
     * 按最终状态批量 upsert 收藏事实，同一用户同一篇笔记只会写一次。
     */
    void saveCollections(@Param("rows") List<NoteCollectionDO> rows);

    /**
     * 统计一篇笔记当前有效的点赞数，供删除时冻结扣减快照。
     */
    long countLikes(@Param("noteId") long noteId);

    /**
     * 统计一篇笔记当前有效的收藏数，供删除时冻结扣减快照。
     */
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
