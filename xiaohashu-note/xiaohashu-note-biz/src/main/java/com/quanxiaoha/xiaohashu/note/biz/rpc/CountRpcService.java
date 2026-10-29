package com.quanxiaoha.xiaohashu.note.biz.rpc;

import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.count.api.CountFeignApi;
import com.quanxiaoha.xiaohashu.count.api.dto.*;
import com.quanxiaoha.xiaohashu.note.biz.domain.dataobject.NoteInteractionResultDO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class CountRpcService {
    @Resource
    private CountFeignApi countFeignApi;

    @org.springframework.beans.factory.annotation.Value("${count.internal-token}")
    private String internalToken;

    /** 用持久化的实际增量更新笔记和作者两个目标，重复请求沿用同一事件键。 */
    public void applyInteractionResults(List<NoteInteractionResultDO> results) {
        List<CountChangeDTO> changes = new ArrayList<>();
        for (NoteInteractionResultDO result : results) {
            if (result.getActualDelta() == 0) {
                continue;
            }
            String eventKey = "interaction:" + result.getUserId() + ":" + result.getRequestId();
            boolean like = "LIKE".equals(result.getActionType());
            long delta = result.getActualDelta();
            changes.add(new CountChangeDTO(result.getCountGeneration(), eventKey, "note", result.getNoteId(),
                    Map.of(like ? "likeTotal" : "collectTotal", delta)));
            changes.add(new CountChangeDTO(result.getCountGeneration(), eventKey, "user", result.getAuthorId(),
                    Map.of(like ? "likesReceivedTotal" : "collectsReceivedTotal", delta)));
        }
        // 分批部分成功也可重试整个请求，成功目标的 seen 集合会拦截重复事件。
        for (int offset = 0; offset < changes.size(); offset += 200) {
            Response<Void> response = countFeignApi.apply(new CountChangeBatchDTO(
                    changes.subList(offset, Math.min(offset + 200, changes.size()))), internalToken);
            if (response == null || !response.isSuccess()) {
                throw new IllegalStateException("计数更新失败");
            }
        }
    }

    /** 查询笔记实时计数，调用失败交由调用方按场景处理。 */
    public NoteCountDTO findNote(long noteId) {
        Response<NoteCountDTO> response = countFeignApi.findNote(noteId);
        if (response == null || !response.isSuccess() || response.getData() == null) {
            throw new IllegalStateException("笔记计数查询失败");
        }
        return response.getData();
    }
}
