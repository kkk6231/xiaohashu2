package com.quanxiaoha.xiaohashu.count.constant;

/**
 * 计数服务 API 层公共常量。
 */
public interface ApiConstants {

    /**
     * Nacos 中注册的计数服务名称，Feign 根据该名称完成服务发现。
     */
    String SERVICE_NAME = "xiaohashu-count";
}
