package com.quanxiaoha.xiaohashu.gateway.auth;

/**
 * APP 端权限标识。
 */
public final class PermissionConstants {

    public static final String NOTE_PUBLISH = "app:note:publish";
    public static final String COMMENT_PUBLISH = "app:comment:publish";
    public static final String FILE_UPLOAD = "app:file:upload";
    public static final String SOCIAL_INTERACT = "app:social:interact";
    public static final String RELATION_WRITE = "app:relation:write";
    public static final String USER_MANAGE = "app:user:manage";
    public static final String INTERNAL_ACCESS = "internal:access";

    /**
     * 工具类不允许实例化。
     */
    private PermissionConstants() {
    }
}
