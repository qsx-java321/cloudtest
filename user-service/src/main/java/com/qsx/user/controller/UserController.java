package com.qsx.user.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.qsx.user.client.OrderClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class UserController {

    private final OrderClient orderClient;

    public UserController(OrderClient orderClient) {
        this.orderClient = orderClient;
    }

    @GetMapping("/user/local")
    @SentinelResource(value = "userLocalApi", blockHandlerClass = UserControllerBlockHandler.class, blockHandler = "userLocalBlock")
    public ResponseEntity<String> userLocal() {
        log.info("[User服务] 执行本地用户查询逻辑");
        return ResponseEntity.ok("【用户服务】测试用户ID：001，姓名：测试用户");
    }

    @GetMapping("/user/callOrder")
    @SentinelResource(value = "userCallOrderApi", blockHandlerClass = UserControllerBlockHandler.class, blockHandler = "userCallOrderBlock")
    public ResponseEntity<String> callOrder() {
        log.info("[User服务] 开始远程调用order服务接口");
        String orderResp = orderClient.getOrderInfo();
        return ResponseEntity.ok("【用户服务】远程调用订单结果：" + orderResp);
    }
}
