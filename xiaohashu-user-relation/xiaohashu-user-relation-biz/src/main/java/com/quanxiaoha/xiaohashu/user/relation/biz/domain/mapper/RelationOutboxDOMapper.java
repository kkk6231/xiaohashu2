package com.quanxiaoha.xiaohashu.user.relation.biz.domain.mapper;

import com.quanxiaoha.xiaohashu.user.relation.biz.domain.dataobject.RelationOutboxDO;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface RelationOutboxDOMapper {
    int insert(RelationOutboxDO row);

    int payload(RelationOutboxDO row);

    List<Long> candidates(@Param("limit") int limit);

    // 领取时锁定关系行，防止同一关系的多个投递器并发领取。
    Long lockRelation(@Param("id") long id);

    RelationOutboxDO head(@Param("id") long id);

    int claim(
            @Param("id") long id,
            @Param("token") String token,
            @Param("leaseMicros") long leaseMicros);

    int sent(@Param("id") long id, @Param("token") String token);

    int failed(
            @Param("id") long id,
            @Param("token") String token,
            @Param("delay") int delay,
            @Param("error") String error);
}
