package com.qsx.gateway.config;

import com.alibaba.csp.sentinel.adapter.spring.webflux.callback.BlockRequestHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.nio.charset.StandardCharsets;

@Configuration
public class SentinelWebFluxConfig {

    @Bean
    public BlockRequestHandler sentinelBlockRequestHandler() {
        return (exchange, ex) -> {
            String msg = "请求被限流，请稍后重试";
            return ServerResponse
                    .status(HttpStatus.TOO_MANY_REQUESTS.value())  // 用 int
                    .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))       // 根据需要改为 TEXT_PLAIN
                    .bodyValue(msg);
        };
    }
}