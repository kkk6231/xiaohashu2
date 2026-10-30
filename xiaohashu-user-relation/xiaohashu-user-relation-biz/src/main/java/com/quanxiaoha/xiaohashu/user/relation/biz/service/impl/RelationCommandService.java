package com.quanxiaoha.xiaohashu.user.relation.biz.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.xiaohashu.user.relation.api.event.RelationEvent;
import com.quanxiaoha.xiaohashu.user.relation.biz.config.RelationProperties;
import com.quanxiaoha.xiaohashu.user.relation.biz.domain.dataobject.RelationDO;
import com.quanxiaoha.xiaohashu.user.relation.biz.domain.dataobject.RelationOutboxDO;
import com.quanxiaoha.xiaohashu.user.relation.biz.domain.mapper.RelationDOMapper;
import com.quanxiaoha.xiaohashu.user.relation.biz.domain.mapper.RelationOutboxDOMapper;
import com.quanxiaoha.xiaohashu.user.relation.biz.enums.FollowUnfollowTypeEnum;
import com.quanxiaoha.xiaohashu.user.relation.biz.enums.ResponseCodeEnum;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * @description: 关注与取关写入服务，负责关系主表和本地消息的事务一致性。
 */
@Service
public class RelationCommandService {
    @Resource
    private RelationDOMapper relationDOMapper;
    @Resource
    private RelationOutboxDOMapper relationOutboxDOMapper;
    @Resource
    private TransactionTemplate transactionTemplate;
    @Resource
    private RelationOutboxPublisher relationOutboxPublisher;
    @Resource
    private RelationProperties relationProperties;
    @Resource
    private ObjectMapper objectMapper;

    /** 更新关系状态，并在提交成功后触发即时投递。 */
    public boolean change(long fromUserId, long toUserId, boolean followOperation) {
        Long changedRelationId = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                changedRelationId = transactionTemplate.execute(transactionStatus ->
                        changeInTransaction(fromUserId, toUserId, followOperation));
                break;
            } catch (DuplicateKeyException | OptimisticLockingFailureException exception) {
                // 唯一键冲突或版本冲突时，前一事务已回滚；下一次 execute 重新读取主库状态
                if (attempt == 2) {
                    throw exception;
                }
            }
        }
        // 即时投递用于降低延迟，定时扫描负责补偿已提交但尚未发送的事件。
        if (changedRelationId != null) {
            relationOutboxPublisher.wake(changedRelationId);
        }
        return followOperation;
    }

    /** 在同一事务中修改关系事实并记录 Outbox 事件。 */
    private Long changeInTransaction(
            long fromUserId, long toUserId, boolean followOperation) {
        RelationDO relationDO = relationDOMapper.following(fromUserId, toUserId);
        // 已是目标状态时直接返回，避免重复生成关系事件
        boolean alreadyFollowing = relationDO != null
                && FollowUnfollowTypeEnum.FOLLOW.getCode().equals(relationDO.getType());
        if (alreadyFollowing == followOperation) {
            return null;
        }

        // 只有"关注"需要用户行锁：要挡的是跨行约束（关注数上限）；
        // 同一对关系的并发由下面的 version CAS 兜住，所以取关不必加锁。
        if (followOperation) {
            // 锁住该用户自己的一行，使"数上限 + 写入"在事务内串行；
            // 不同用户锁的是不同的行，互不阻塞。锁随事务提交或回滚释放。
            relationDOMapper.lockUser(fromUserId);
            // 上限以关系主表为准，不能依赖异步计数
            if (relationDOMapper.followingCount(fromUserId) >= relationProperties.getMaxFollowing()) {
                throw new BizException(ResponseCodeEnum.FOLLOWING_COUNT_LIMIT);
            }
        }
        // 取关保留关系记录，再次关注继续递增版本，防止旧消息覆盖新状态
        if (relationDO == null) {
            relationDO = new RelationDO();
            relationDO.setFromUserId(fromUserId);
            relationDO.setToUserId(toUserId);
            relationDO.setType(FollowUnfollowTypeEnum.FOLLOW.getCode());
            relationDO.setVersion(1L);
            relationDO.setUpdateTime(relationDOMapper.now());
            relationDOMapper.insert(relationDO);
        } else {
            Integer relationType = FollowUnfollowTypeEnum.UNFOLLOW.getCode();
            if (followOperation) {
                relationType = FollowUnfollowTypeEnum.FOLLOW.getCode();
            }
            relationDO.setType(relationType);
            relationDO.setVersion(relationDO.getVersion() + 1);
            relationDO.setUpdateTime(relationDOMapper.now());
            if (relationDOMapper.update(relationDO) != 1) {
                throw new OptimisticLockingFailureException("关系版本已变化，请重试");
            }
        }
        // 关系变更和本地消息必须由同一个数据库事务提交
        appendEvent(relationDO);
        return relationDO.getId();
    }

    /** 分配事件 ID 后回填消息体；任一步失败都回滚整笔事务。 */
    private void appendEvent(RelationDO relationDO) {
        RelationEvent relationEvent = new RelationEvent();
        relationEvent.setRelationId(relationDO.getId());
        relationEvent.setFromUserId(relationDO.getFromUserId());
        relationEvent.setToUserId(relationDO.getToUserId());
        relationEvent.setType(relationDO.getType());
        relationEvent.setRelationVersion(relationDO.getVersion());
        relationEvent.setUpdateTime(relationDO.getUpdateTime());

        RelationOutboxDO outboxDO = new RelationOutboxDO();
        outboxDO.setRelationId(relationDO.getId());
        outboxDO.setRelationVersion(relationDO.getVersion());
        // 先插入以获取数据库生成的 eventId，再将它写入消息体
        // 两步都在当前事务内，投递器只能看到提交后的完整消息
        outboxDO.setPayload("{}");
        relationOutboxDOMapper.insert(outboxDO);
        relationEvent.setEventId(outboxDO.getEventId());
        try {
            outboxDO.setPayload(objectMapper.writeValueAsString(relationEvent));
        } catch (Exception exception) {
            throw new IllegalStateException("关系事件序列化失败", exception);
        }
        if (relationOutboxDOMapper.payload(outboxDO) != 1) {
            throw new IllegalStateException("回填本地消息内容失败");
        }
    }
}
