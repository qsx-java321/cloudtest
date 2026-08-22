package com.qsx.user.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

@FeignClient(name = "order-service", fallbackFactory = OrderClientFallbackFactory.class)
public interface OrderClient {

    @GetMapping("/order/get")
    String getOrderInfo();
}
