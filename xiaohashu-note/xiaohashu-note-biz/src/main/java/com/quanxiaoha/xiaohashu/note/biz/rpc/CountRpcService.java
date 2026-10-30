package com.quanxiaoha.xiaohashu.note.biz.rpc;

import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.count.api.CountFeignApi;
import com.quanxiaoha.xiaohashu.count.api.dto.*;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class CountRpcService {
    @Resource
    private CountFeignApi countFeignApi;

    /** 查询笔记实时计数，调用失败交由调用方按场景处理。 */
    public NoteCountDTO findNote(long noteId) {
        Response<NoteCountDTO> response = countFeignApi.findNote(noteId);
        if (response == null || !response.isSuccess() || response.getData() == null) {
            throw new IllegalStateException("笔记计数查询失败");
        }
        return response.getData();
    }
}
