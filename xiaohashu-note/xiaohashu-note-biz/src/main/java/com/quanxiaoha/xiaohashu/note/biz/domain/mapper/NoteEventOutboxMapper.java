package com.quanxiaoha.xiaohashu.note.biz.domain.mapper;

import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteEventOutboxDO;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface NoteEventOutboxMapper {
    void insert(NoteEventOutboxDO row);
    void updatePayload(NoteEventOutboxDO row);
    List<NoteEventOutboxDO> candidates();
    int claim(@Param("id") long id, @Param("token") String token);
    void sent(@Param("id") long id, @Param("token") String token);
    void failed(@Param("id") long id, @Param("token") String token);
}
