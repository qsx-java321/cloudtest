package com.qsx.order.controller;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

@Slf4j
public class OrderControllerBlockHandler {

    public static Mono<ResponseEntity<String>> orderBlockHandler(BlockException e) {
        log.info("[Order服务] orderGetApi接口触发单机限流");
        return Mono.just(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body("【接口专属限流】订单查询请求过于频繁，请稍后访问"));
    }
}