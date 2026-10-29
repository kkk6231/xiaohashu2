package com.quanxiaoha.xiaohashu.user.relation.biz.rpc;

import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.framework.common.exception.BizException;
import com.quanxiaoha.xiaohashu.user.relation.biz.enums.ResponseCodeEnum;
import com.quanxiaoha.xiaohashu.count.api.RelationCountDTO;
import com.quanxiaoha.xiaohashu.count.api.RelationCountFeignApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/** 关系计数 RPC，沿用用户服务的 RPC 封装方式。 */
@Component
public class RelationCountRpcService {
    @Resource
    private RelationCountFeignApi relationCountFeignApi;

    public Response<RelationCountDTO> findCount(long userId) {
        return relationCountFeignApi.find(userId);
    }

    /** 总数允许与粉丝列表短暂不同步；RPC 失败不能伪装成总数为零。 */
    public long findFansTotal(long userId) {
        Response<RelationCountDTO> response = findCount(userId);
        if (response == null || !response.isSuccess() || response.getData() == null) {
            throw new BizException(ResponseCodeEnum.SYSTEM_ERROR);
        }
        return response.getData().getFansTotal();
    }
}
