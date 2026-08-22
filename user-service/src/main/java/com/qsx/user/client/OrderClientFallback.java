package com.qsx.user.client;

import lombok.extern.slf4j.Slf4j;

/**
 * OpenFeign 降级实现：当 order-service 触发限流、异常或不可用时返回兜底结果。
 * 由 {@link OrderClientFallbackFactory} 创建并注入异常原因，不直接作为 Spring Bean。
 */
@Slf4j
public class OrderClientFallback implements OrderClient {

    private final Throwable cause;

    public OrderClientFallback(Throwable cause) {
        this.cause = cause;
    }

    @Override
    public String getOrderInfo() {
        log.warn("[User服务] 远程调用order服务失败，触发Feign降级，原因：{}", cause == null ? "未知" : cause.getMessage());
        return "【降级兜底】订单服务暂时不可用，已返回兜底数据";
    }
}
