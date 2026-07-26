package com.qsx.user.config;

import com.qsx.user.client.OrderClient;
import org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerExchangeFilterFunction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient(ReactorLoadBalancerExchangeFilterFunction lbFilter) {
        return WebClient.builder()
                .filter(lbFilter)
                .baseUrl("lb://order-service")
                .build();
    }

    @Bean
    public OrderClient orderClient(WebClient webClient) {
        return HttpServiceProxyFactory
                .builderFor(WebClientAdapter.create(webClient))
                .build()
                .createClient(OrderClient.class);
    }
}