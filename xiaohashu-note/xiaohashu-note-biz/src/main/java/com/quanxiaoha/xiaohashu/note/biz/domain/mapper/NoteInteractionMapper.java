package com.quanxiaoha.xiaohashu.note.biz.domain.mapper;

import org.apache.ibatis.annotations.Param;

/** 笔记删除时读取互动数量快照。 */
public interface NoteInteractionMapper {
    /** 读取笔记的作者和状态，供删除操作使用。 */
    com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteDO findNote(@Param("noteId") long noteId);
    /** 统计笔记当前有效点赞。 */
    long countLikes(@Param("noteId") long noteId);
    /** 统计笔记当前有效收藏。 */
    long countCollections(@Param("noteId") long noteId);
}
