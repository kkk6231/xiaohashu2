package com.quanxiaoha.xiaohashu.user.relation.biz.domain.mapper;

import com.quanxiaoha.xiaohashu.user.relation.biz.domain.dataobject.RelationDO;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

public interface RelationDOMapper {

    int lockUser(@Param("user") long user);

    RelationDO following(@Param("from") long from, @Param("to") long to);

    RelationDO follower(@Param("from") long from, @Param("to") long to);

    long followingCount(@Param("user") long user);

    int insert(RelationDO row);

    int update(RelationDO row);

    LocalDateTime now();

    List<RelationDO> followingList(@Param("user") long user);

    List<RelationDO> followerPage(
            @Param("user") long user, @Param("offset") long offset, @Param("limit") int limit);

    List<RelationDO> followerAfter(
            @Param("user") long user,
            @Param("time") LocalDateTime time,
            @Param("id") long id,
            @Param("limit") int limit);

    // 版本字段最后赋值，避免 MySQL 从左到右求值时影响状态判断。
    int project(RelationDO row);

    List<Long> followerStatuses(@Param("user") long user, @Param("ids") List<Long> ids);

    List<Long> followingStatuses(@Param("user") long user, @Param("ids") List<Long> ids);
}
