package com.quanxiaoha.xiaohashu.note.biz.service.impl;

import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteInteractionResultDO;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteInteractionMapper;
import com.quanxiaoha.xiaohashu.note.biz.rpc.CountRpcService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;

/** 使用互动结果表补偿计数，不新增 MQ 层级，也不在等待 RPC 时持有数据库事务。 */
@Component
@Slf4j
public class NoteInteractionCountCompensator {
    @Resource
    private NoteInteractionMapper interactionMapper;
    @Resource
    private CountRpcService countRpcService;

    /** 每次最多补偿 100 条；确认失败或进程中断时保持待处理，成功目标由 Redis 去重。 */
    @Scheduled(fixedDelayString = "${note.interaction.count-retry-delay-millis:1000}")
    public void scan() {
        List<NoteInteractionResultDO> pendingResults;
        try {
            pendingResults = interactionMapper.findPendingCounts(100);
        } catch (Exception exception) {
            log.error("读取互动计数待处理任务失败，下次扫描重试", exception);
            return;
        }
        if (pendingResults.isEmpty()) {
            return;
        }
        try {
            // 不重新计算增量、不生成新事件键、不改变代次，沿用事实提交时的结果。
            countRpcService.applyInteractionResults(pendingResults);
            interactionMapper.completeCounts(pendingResults);
        } catch (Exception exception) {
            log.error("互动计数补偿失败，保留待处理任务，条数：{}", pendingResults.size(), exception);
            try {
                interactionMapper.retryCounts(pendingResults);
            } catch (Exception retryException) {
                // 连失败状态都写不回时，原来的待处理记录仍在，下一轮扫描会再次找到。
                log.error("记录计数补偿重试时间失败，原任务仍保持待处理", retryException);
            }
        }
    }
}
