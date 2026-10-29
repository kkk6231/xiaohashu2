package com.quanxiaoha.xiaohashu.note.biz.controller;

import com.quanxiaoha.framework.biz.context.holder.LoginUserContextHolder;
import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.note.biz.domain.mapper.NoteInteractionMapper;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class NoteInteractionController {
    @Resource
    private NoteInteractionMapper interactionMapper;

    @GetMapping("/note/interaction/result")
    /**
     * 查询某次互动请求的处理结果，只允许查询自己的 requestId。
     * 尚未生成处理记录时返回 UNKNOWN，不能据此判断消息丢失或失败。
     */
    public Response<?> findResult(@RequestParam String requestId) {
        var result = interactionMapper.findResult(LoginUserContextHolder.getUserId(), requestId);
        if (result == null) return Response.success(Map.of("requestId", requestId, "status", "UNKNOWN"));
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("requestId", requestId);
        data.put("status", result.getResultType());
        data.put("reasonCode", result.getReasonCode());
        data.put("targetState", result.getTargetState());
        data.put("actualDelta", result.getActualDelta());
        return Response.success(data);
    }
}
