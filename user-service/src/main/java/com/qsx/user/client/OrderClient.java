package com.qsx.user.client;

import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import reactor.core.publisher.Mono;

@HttpExchange
public interface OrderClient {

    @GetExchange("/order/get")
    Mono<String> getOrderInfo();
}