package com.qsx.order.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class OrderController {

    @GetMapping("/order/get")
    @SentinelResource(value = "orderGetApi", blockHandlerClass = OrderControllerBlockHandler.class, blockHandler = "orderBlockHandler")
    public ResponseEntity<String> getOrder() {
        log.info("[Order服务] 正常处理订单查询请求");
        return ResponseEntity.ok("【订单服务】测试订单号：10001，状态：正常");
    }
}
