package com.qsx.user.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * SentinelFeign 降级工厂：order-service 不可用/限流时创建降级实例并传入异常原因。
 * 需开启 feign.sentinel.enabled=true，Sentinel 才会接管 Feign 的降级处理。
 */
@Slf4j
@Component
public class OrderClientFallbackFactory implements FallbackFactory<OrderClientFallback> {

    @Override
    public OrderClientFallback create(Throwable cause) {
        return new OrderClientFallback(cause);
    }
}
