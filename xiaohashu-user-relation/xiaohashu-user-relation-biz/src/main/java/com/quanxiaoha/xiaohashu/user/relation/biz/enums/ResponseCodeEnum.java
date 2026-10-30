package com.quanxiaoha.xiaohashu.user.relation.biz.enums;

import com.quanxiaoha.framework.common.exception.BaseExceptionInterface;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @author: 犬小哈
 * @url: www.quanxiaoha.com
 * @date: 2023-08-15 10:33
 * @description: 响应异常码
 **/
@Getter
@AllArgsConstructor
public enum ResponseCodeEnum implements BaseExceptionInterface {

    // ----------- 通用异常状态码 -----------
    SYSTEM_ERROR("RELATION-10000", "出错啦，后台小哥正在努力修复中..."),
    PARAM_NOT_VALID("RELATION-10001", "参数错误"),

    // ----------- 业务异常状态码 -----------
    CANT_FOLLOW_YOUR_SELF("RELATION-20001", "无法关注自己"),
    FOLLOW_USER_NOT_EXISTED("RELATION-20002", "关注的用户不存在"),
    FOLLOWING_COUNT_LIMIT("RELATION-20003", "您关注的用户已达上限，请先取关部分用户"),
    CANT_UNFOLLOW_YOUR_SELF("RELATION-20005", "无法取关自己"),
    RELATION_BUSY("RELATION-20007", "操作繁忙，请稍后重试"),
    LOGIN_REQUIRED("RELATION-20008", "请先登录"),
    FANS_PAGE_LIMIT_EXCEEDED("RELATION-20009", "粉丝过多，仅支持查看最近的部分粉丝"),
    ;

    // 异常码
    private final String errorCode;
    // 错误信息
    private final String errorMessage;

}
