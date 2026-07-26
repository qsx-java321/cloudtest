package com.qsx.order.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
public class OrderController {

    @GetMapping("/order/get")
    @SentinelResource(value = "orderGetApi", blockHandlerClass = OrderControllerBlockHandler.class, blockHandler = "orderBlockHandler")
    public Mono<ResponseEntity<String>> getOrder() {
        log.info("[Order服务] 正常处理订单查询请求");
        return Mono.just(ResponseEntity.ok("【订单服务】测试订单号：10001，状态：正常"));
    }
}
