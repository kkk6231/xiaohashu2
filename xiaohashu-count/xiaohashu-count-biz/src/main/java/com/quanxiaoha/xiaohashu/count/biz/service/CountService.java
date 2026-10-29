package com.quanxiaoha.xiaohashu.count.biz.service;

import com.quanxiaoha.xiaohashu.count.api.dto.*;
import java.util.List;

public interface CountService {
    /**
     * 批量应用计数增量。
     * 调用方在整批成功后才确认消息；失败时整批重试，已处理的目标由 seen 集合跳过。
     */
    void apply(List<CountChangeDTO> changes);

    /**
     * 批量查询用户五项计数（关注、粉丝、获赞、获收藏、作品）。
     * 返回顺序与入参一致；目标从未收到过事件时返回 0。
     */
    List<UserCountDTO> findUsers(List<Long> ids);

    /**
     * 批量查询作品的点赞、收藏计数，返回顺序与入参一致。
     */
    List<NoteCountDTO> findNotes(List<Long> ids);
}
