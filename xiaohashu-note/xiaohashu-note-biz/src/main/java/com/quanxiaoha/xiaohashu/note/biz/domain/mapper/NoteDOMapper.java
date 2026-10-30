package com.quanxiaoha.xiaohashu.note.biz.domain.mapper;

import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteDO;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;

public interface NoteDOMapper {
    /** 按读取到的状态条件删除，返回 1 才代表本次请求完成状态迁移。 */
    int markDeletedIfStatus(@Param("noteId") Long noteId,
            @Param("creatorId") Long creatorId,
            @Param("expectedStatus") Integer expectedStatus,
            @Param("updateTime") LocalDateTime updateTime);

    int deleteByPrimaryKey(Long id);

    int insert(NoteDO record);

    int insertSelective(NoteDO record);

    NoteDO selectByPrimaryKey(Long id);

    int selectCountByNoteId(Long noteId);

    /**
     * 查询笔记的发布者用户 ID
     * @param noteId
     * @return
     */
    Long selectCreatorIdByNoteId(Long noteId);

    int updateByPrimaryKeySelective(NoteDO record);

    int updateByPrimaryKey(NoteDO record);

    int updateVisibleOnlyMe(NoteDO noteDO);

    int updateIsTop(NoteDO noteDO);

    /** 批量读取当前用户可见的笔记摘要，供互动列表缓存使用。 */
    java.util.List<com.quanxiaoha.xiaohashu.note.biz.model.vo.FindInteractionNoteListRspVO> selectVisibleNotesByIds(
            @Param("userId") Long userId, @Param("noteIds") java.util.List<Long> noteIds);

}