package com.qsx.order.config;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.SentinelWebInterceptor;
import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.BlockExceptionHandler;
import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.config.SentinelWebMvcConfig;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;

/**
 * Sentinel URL 层兜底（webmvc v6x 适配）：
 * 通过 SentinelWebInterceptor 对每个 URL 做流控，被限流时由注册的 BlockExceptionHandler 统一返回 429。
 * 注意：handler 必须通过 SentinelWebMvcConfig#setBlockExceptionHandler 绑定到拦截器，
 * 否则默认行为是直接抛出 BlockException，本自定义兜底不会生效。
 * 注意：本类命名为 SentinelWebMvcInterceptorConfig，避免与 Sentinel 官方的
 * com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.config.SentinelWebMvcConfig 简单类名冲突。
 */
@Configuration
public class SentinelWebMvcInterceptorConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        SentinelWebMvcConfig config = new SentinelWebMvcConfig();
        config.setBlockExceptionHandler(sentinelBlockExceptionHandler());
        registry.addInterceptor(new SentinelWebInterceptor(config)).addPathPatterns("/**");
    }

    @Bean
    public BlockExceptionHandler sentinelBlockExceptionHandler() {
        return new BlockExceptionHandler() {
            @Override
            public void handle(HttpServletRequest request, HttpServletResponse response, String resourceName, BlockException e) throws Exception {
                response.setStatus(429);
                response.setContentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8).toString());
                response.getWriter().write("请求被Sentinel流量限制：当前访问量超出阈值，请稍后重试");
            }
        };
    }
}
