package com.quanxiaoha.xiaohashu.count.biz.service;

import com.quanxiaoha.xiaohashu.count.api.dto.*;
import java.util.List;

public interface CountService {
    void apply(List<CountChangeDTO> changes);
    List<UserCountDTO> findUsers(List<Long> ids);
    List<NoteCountDTO> findNotes(List<Long> ids);
}
