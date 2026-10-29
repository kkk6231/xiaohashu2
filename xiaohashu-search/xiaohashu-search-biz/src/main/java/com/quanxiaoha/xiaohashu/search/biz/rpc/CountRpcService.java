package com.quanxiaoha.xiaohashu.search.biz.rpc;

import com.quanxiaoha.framework.common.util.NumberUtils;
import com.quanxiaoha.xiaohashu.count.api.CountFeignApi;
import com.quanxiaoha.xiaohashu.search.biz.model.vo.SearchNoteRspVO;
import com.quanxiaoha.xiaohashu.search.biz.model.vo.SearchUserRspVO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class CountRpcService {
    @Resource
    private CountFeignApi countFeignApi;

    /** 搜索索引只负责召回，展示计数按当前页批量读取。 */
    public void fillNotes(List<SearchNoteRspVO> notes) {
        if (notes == null || notes.isEmpty()) return;
        for (int offset = 0; offset < notes.size(); offset += 100) {
            var page = notes.subList(offset, Math.min(offset + 100, notes.size()));
            var response = countFeignApi.findNotes(Map.of("noteIds", page.stream().map(SearchNoteRspVO::getNoteId).toList()));
            if (response == null || !response.isSuccess() || response.getData() == null) {
                throw new IllegalStateException("搜索笔记计数查询失败");
            }
            Map<Long, com.quanxiaoha.xiaohashu.count.api.dto.NoteCountDTO> counts = new HashMap<>();
            response.getData().forEach(count -> counts.put(count.getNoteId(), count));
            for (var note : page) {
                var count = counts.get(note.getNoteId());
                note.setLikeTotal(NumberUtils.formatNumberString(count.getLikeTotal()));
                note.setCollectTotal(NumberUtils.formatNumberString(count.getCollectTotal()));
            }
        }
    }

    public void fillUsers(List<SearchUserRspVO> users) {
        if (users == null || users.isEmpty()) return;
        for (int offset = 0; offset < users.size(); offset += 100) {
            var page = users.subList(offset, Math.min(offset + 100, users.size()));
            var response = countFeignApi.findUsers(Map.of("userIds", page.stream().map(SearchUserRspVO::getUserId).toList()));
            if (response == null || !response.isSuccess() || response.getData() == null) {
                throw new IllegalStateException("搜索用户计数查询失败");
            }
            Map<Long, com.quanxiaoha.xiaohashu.count.api.dto.UserCountDTO> counts = new HashMap<>();
            response.getData().forEach(count -> counts.put(count.getUserId(), count));
            for (var user : page) {
                var count = counts.get(user.getUserId());
                user.setNoteTotal(count.getWorksTotal());
                user.setFansTotal(NumberUtils.formatNumberString(count.getFansTotal()));
            }
        }
    }
    public void fillNoteIndex(List<Map<String, Object>> rows) {
        for (int offset = 0; offset < rows.size(); offset += 100) {
            var page = rows.subList(offset, Math.min(offset + 100, rows.size()));
            var ids = page.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
            var response = countFeignApi.findNotes(Map.of("noteIds", ids));
            if (response == null || !response.isSuccess() || response.getData() == null) {
                throw new IllegalStateException("笔记索引计数查询失败");
            }
            Map<Long, com.quanxiaoha.xiaohashu.count.api.dto.NoteCountDTO> counts = new HashMap<>();
            response.getData().forEach(count -> counts.put(count.getNoteId(), count));
            page.forEach(row -> {
                var count = counts.get(((Number) row.get("id")).longValue());
                row.put("like_total", count.getLikeTotal());
                row.put("collect_total", count.getCollectTotal());
            });
        }
    }

    public void fillUserIndex(List<Map<String, Object>> rows) {
        for (int offset = 0; offset < rows.size(); offset += 100) {
            var page = rows.subList(offset, Math.min(offset + 100, rows.size()));
            var ids = page.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
            var response = countFeignApi.findUsers(Map.of("userIds", ids));
            if (response == null || !response.isSuccess() || response.getData() == null) {
                throw new IllegalStateException("用户索引计数查询失败");
            }
            Map<Long, com.quanxiaoha.xiaohashu.count.api.dto.UserCountDTO> counts = new HashMap<>();
            response.getData().forEach(count -> counts.put(count.getUserId(), count));
            page.forEach(row -> {
                var count = counts.get(((Number) row.get("id")).longValue());
                row.put("fans_total", count.getFansTotal());
                row.put("note_total", count.getWorksTotal());
            });
        }
    }

}
