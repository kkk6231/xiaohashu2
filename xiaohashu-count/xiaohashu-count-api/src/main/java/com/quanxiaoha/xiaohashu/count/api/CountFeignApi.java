package com.quanxiaoha.xiaohashu.count.api;

import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.count.api.dto.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "xiaohashu-count", contextId = "counts")
public interface CountFeignApi {
    @PostMapping("/internal/count/changes/batch")
    Response<Void> apply(@RequestBody CountChangeBatchDTO batch,
                         @RequestHeader("X-Count-Token") String token);

    @GetMapping("/count/user")
    Response<UserCountDTO> findUser(@RequestParam("userId") long userId);

    @GetMapping("/count/note")
    Response<NoteCountDTO> findNote(@RequestParam("noteId") long noteId);

    @PostMapping("/count/note/batch")
    Response<java.util.List<NoteCountDTO>> findNotes(@RequestBody java.util.Map<String, java.util.List<Long>> body);

    @PostMapping("/count/user/batch")
    Response<java.util.List<UserCountDTO>> findUsers(@RequestBody java.util.Map<String, java.util.List<Long>> body);
}
