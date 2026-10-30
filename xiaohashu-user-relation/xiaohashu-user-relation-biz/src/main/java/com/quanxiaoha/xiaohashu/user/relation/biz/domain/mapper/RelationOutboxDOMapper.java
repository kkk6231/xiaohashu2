package com.quanxiaoha.xiaohashu.user.relation.biz.domain.mapper;

import com.quanxiaoha.xiaohashu.user.relation.biz.domain.dataobject.RelationOutboxDO;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface RelationOutboxDOMapper {
    /**
     * 插入一条待发送事件，主键（同时也是 eventId）回写到入参对象。
     * 与业务事实处于同一个事务：事实回滚时事件一起回滚。
     */
    int insert(RelationOutboxDO row);

    /**
     * 回填事件内容（payload）。
     * 先插入占位行拿到自增主键，再把包含 eventId 的完整事件写回来，两步同事务。
     */
    int payload(RelationOutboxDO row);

    /**
     * 扫描待投递的关系 ID：包括到期的待发送记录和租约过期的“发送中”记录。
     * 只返回关系 ID 去重后的结果，实际发送仍按事件逐条推进。
     */
    List<Long> candidates(@Param("limit") int limit);

    /**
     * 取该关系下最早的一条未发送事件（按版本升序）。
     * 不能跳过前面的版本直接领后面的，否则投递顺序会乱。
     * 语句本身是 FOR UPDATE，并发领取在这里串行。
     */
    RelationOutboxDO head(@Param("id") long id);

    /**
     * 把一条事件标记为“发送中”：写入 claimToken 和租约截止时间。
     * 带状态与重试时间条件，并发领取时只有一个能成功。
     *
     * @return 更新行数，1 表示领取成功
     */
    int claim(
            @Param("id") long id,
            @Param("token") String token,
            @Param("leaseMicros") long leaseMicros);

    /**
     * 标记发送成功。
     * 必须同时匹配 claimToken：租约过期后旧持有者不能覆盖新持有者的状态。
     */
    int sent(@Param("id") long id, @Param("token") String token);

    /**
     * 记录发送失败：累加重试次数、按指数退避设置下次重试时间、保存错误摘要。
     * 不删除事件，等待扫描器再次领取。
     */
    int failed(
            @Param("id") long id,
            @Param("token") String token,
            @Param("delay") int delay,
            @Param("error") String error);
}
