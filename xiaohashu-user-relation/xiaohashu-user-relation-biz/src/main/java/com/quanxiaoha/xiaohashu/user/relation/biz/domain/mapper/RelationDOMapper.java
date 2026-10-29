package com.quanxiaoha.xiaohashu.user.relation.biz.domain.mapper;

import com.quanxiaoha.xiaohashu.user.relation.biz.domain.dataobject.RelationDO;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

public interface RelationDOMapper {

    /**
     * 取得用户维度的行锁。
     * 用“插入或同值更新”的方式加锁：行不存在会先建出来，存在则拿排他锁，
     * 保证同一用户的关注上限检查和关系写入在事务内串行。
     */
    int lockUser(@Param("user") long user);

    /**
     * 查询一对用户的关注关系记录（不过滤状态，取关后记录仍保留）。
     */
    RelationDO following(@Param("from") long from, @Param("to") long to);

    /**
     * 查询粉丝投影表里的一条记录，用于缓存增量同步时判断最新状态。
     */
    RelationDO follower(@Param("from") long from, @Param("to") long to);

    /**
     * 统计用户当前有效关注数，作为关注上限的判断依据。
     * 必须以主表为准：异步计数可能延迟，用它判上限会放过超额。
     */
    long followingCount(@Param("user") long user);

    /**
     * 新增一条关系记录，version 从 1 开始；主键回写到入参对象。
     */
    int insert(RelationDO row);

    /**
     * 更新关系状态并递增版本号。
     * WHERE 条件带旧版本号做乐观锁，更新 0 行说明被别人改过，调用方需要重试。
     */
    int update(RelationDO row);

    /**
     * 读取数据库当前时间（毫秒精度），让关系时间以数据库为准，避免多实例时钟不一致。
     */
    LocalDateTime now();

    /**
     * 查询关注列表：只取有效关注，按关注时间倒序、ID 倒序排。
     * 用于缓存初始化，一次读全量（关注有上限）。
     */
    List<RelationDO> followingList(@Param("user") long user);

    /**
     * 分页查询粉丝列表：按关注时间倒序、发起人 ID 倒序，保证翻页稳定不重复。
     *
     * @param offset 偏移量
     * @param limit  本页条数
     */
    List<RelationDO> followerPage(
            @Param("user") long user, @Param("offset") long offset, @Param("limit") int limit);

    /**
     * 按“时间 + ID”边界查询某时间点之后的粉丝，用于补齐被截断的粉丝缓存。
     * 边界条件保证与 ZSet 的排序一致，不会重复或漏人。
     */
    List<RelationDO> followerAfter(
            @Param("user") long user,
            @Param("time") LocalDateTime time,
            @Param("id") long id,
            @Param("limit") int limit);

    /**
     * 写入粉丝投影（t_follower）。
     * 带版本判断，旧版本事件不会覆盖新状态；version 字段放在最后赋值，
     * 避免 MySQL 从左到右求值时影响状态判断。
     */
    int project(RelationDO row);

    /**
     * 批量查询粉丝状态：给定候选人里，谁关注了该用户。走投影表联合索引。
     */
    List<Long> followerStatuses(@Param("user") long user, @Param("ids") List<Long> ids);

    /**
     * 批量查询关注状态：该用户关注了候选人里的谁。走关注表联合索引。
     */
    List<Long> followingStatuses(@Param("user") long user, @Param("ids") List<Long> ids);
}
