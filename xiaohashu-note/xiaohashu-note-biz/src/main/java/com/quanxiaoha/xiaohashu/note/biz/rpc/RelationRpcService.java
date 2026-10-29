package com.quanxiaoha.xiaohashu.note.biz.rpc;

import com.quanxiaoha.framework.common.response.Response;
import jakarta.annotation.Resource;
import com.quanxiaoha.xiaohashu.user.relation.api.RelationFeignApi;
import org.springframework.stereotype.Component;
import java.util.Map;

/** 用户关系服务调用封装。 */
@Component
public class RelationRpcService {
    @Resource
    private RelationFeignApi relationFeignApi;

    /** 获取关注状态；失败时抛出异常，由详情查询降级为未知状态。 */
    public Boolean isFollowing(long userId, long authorId) {
        Response<Map<String, Boolean>> response = relationFeignApi.findFollowingStatus(userId, authorId);
        if (response == null || !response.isSuccess() || response.getData() == null
                || response.getData().get("following") == null) {
            throw new IllegalStateException("关注状态查询失败");
        }
        return response.getData().get("following");
    }
}
