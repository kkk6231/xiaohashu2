package com.quanxiaoha.xiaohashu.note.biz.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.List;

/**
 * @author: 犬小哈
 * @date: 2024/4/7 15:17
 * @version: v1.0.0
 * @description: 查询笔记详情
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FindNoteDetailRspVO {

    private Long id;
    private Long likeTotal;
    private Long collectTotal;
    /** 计数查询是否成功；失败时点赞数和收藏数为空。 */
    private boolean countsAvailable;
    /** 当前访问者是否关注作者；查询失败时为空。 */
    private Boolean following;
    /** 关注状态是否可用，前端据此决定是否启用关注按钮。 */
    private boolean followingAvailable;
    private boolean liked;
    private boolean collected;

    private Integer type;

    private String title;

    private String content;

    private List<String> imgUris;

    private Long topicId;

    private String topicName;

    private Long creatorId;

    private String creatorName;

    private String avatar;

    private String videoUri;

    /**
     * 编辑时间
     */
    private LocalDateTime updateTime;

    /**
     * 是否可见
     */
    private Integer visible;

}
