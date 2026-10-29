package com.quanxiaoha.xiaohashu.note.biz.domain.mapper;

import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.*;
import com.quanxiaoha.xiaohashu.note.biz.model.dto.NoteInteractionCommandDTO;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface NoteInteractionMapper {
    NoteDO lockNote(@Param("noteId") long noteId);
    List<NoteInteractionResultDO> findResults(@Param("commands") List<NoteInteractionCommandDTO> commands);
    NoteInteractionResultDO findResult(@Param("userId") long userId, @Param("requestId") String requestId);
    List<NoteLikeDO> findLikes(@Param("commands") List<NoteInteractionCommandDTO> commands);
    List<NoteCollectionDO> findCollections(@Param("commands") List<NoteInteractionCommandDTO> commands);
    void saveResults(@Param("results") List<NoteInteractionResultDO> results);
    void saveLikes(@Param("rows") List<NoteLikeDO> rows);
    void saveCollections(@Param("rows") List<NoteCollectionDO> rows);
    long countLikes(@Param("noteId") long noteId);
    long countCollections(@Param("noteId") long noteId);
}
