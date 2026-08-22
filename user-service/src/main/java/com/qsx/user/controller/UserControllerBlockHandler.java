package com.qsx.user.controller;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@Slf4j
public class UserControllerBlockHandler {

    public static ResponseEntity<String> userLocalBlock(BlockException e) {
        log.info("[User服务] userLocalApi接口触发限流");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body("【接口限流】用户本地查询访问量过大，请稍后");
    }

    public static ResponseEntity<String> userCallOrderBlock(BlockException e) {
        log.info("[User服务] userCallOrderApi接口触发限流");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body("【接口限流】远程调用订单服务访问量过大，请稍后");
    }
}
