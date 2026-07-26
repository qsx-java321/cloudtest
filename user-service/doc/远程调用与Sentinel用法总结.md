# user-service 远程调用与 Sentinel 用法总结

---

## 一、项目结构

```
user-service/
├── src/main/java/com/qsx/user/
│   ├── controller/
│   │   ├── UserController.java              # 控制器，Sentinel限流入口
│   │   └── UserControllerBlockHandler.java  # Sentinel降级处理类
│   ├── client/
│   │   └── OrderClient.java                 # 声明式远程调用接口
│   └── config/
│       ├── WebClientConfig.java              # WebClient配置（负载均衡）
│       └── SentinelWebFluxConfig.java        # Sentinel全局降级配置
└── src/main/resources/
    └── application.yaml                      # 应用配置
```

---

## 二、远程调用（@HttpExchange + WebClient）

### 2.1 核心文件

#### 1. OrderClient.java - 声明式接口

```java
package com.qsx.user.client;

import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import reactor.core.publisher.Mono;

@HttpExchange
public interface OrderClient {

    @GetExchange("/order/get")
    Mono<String> getOrderInfo();
}
```

**关键点**：
- `@HttpExchange`：标记这是一个 HTTP 服务接口
- `@GetExchange("/order/get")`：声明 GET 请求，路径必须与目标服务的 `@GetMapping` 一致
- 返回 `Mono<String>`：响应式返回，非阻塞

#### 2. WebClientConfig.java - WebClient 配置

```java
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
```

**关键点**：
- `ReactorLoadBalancerExchangeFilterFunction`：负载均衡过滤器
- `baseUrl("lb://order-service")`：通过服务名访问，自动从 Nacos 获取实例
- `HttpServiceProxyFactory`：根据接口注解生成代理实现类

#### 3. UserController.java - 使用远程调用

```java
@RestController
public class UserController {

    private final OrderClient orderClient;

    public UserController(OrderClient orderClient) {
        this.orderClient = orderClient;
    }

    @GetMapping("/user/callOrder")
    public Mono<String> callOrder() {
        return orderClient.getOrderInfo()
                .map(resp -> "【用户服务】远程调用订单结果：" + resp);
    }
}
```

### 2.2 工作流程

```
启动时：
  1. Spring 扫描 @Configuration 类
  2. 创建 WebClient Bean（带负载均衡）
  3. HttpServiceProxyFactory 根据 OrderClient 接口生成代理类
  4. 代理类注册到 Spring 容器

运行时：
  1. 用户访问 /user/callOrder
  2. UserController 注入 OrderClient 代理
  3. 调用 orderClient.getOrderInfo()
  4. 代理解析 @GetExchange 注解，构建 HTTP 请求
  5. WebClient 发送请求（负载均衡选择 order-service 实例）
  6. 返回 Mono<String> 响应
```

### 2.3 路径对应规则

| 目标服务 Controller | OrderClient 接口 |
|---------------------|------------------|
| `@GetMapping("/order/get")` | `@GetExchange("/order/get")` |
| `@PostMapping("/order/create")` | `@PostExchange("/order/create")` |
| `@GetMapping("/order/{id}")` | `@GetExchange("/order/{id}")` |
| `@PathVariable String id` | `@PathVariable String id` |
| `@RequestBody OrderRequest` | `@RequestBody OrderRequest` |

---

## 三、Sentinel 限流

### 3.1 核心文件

#### 1. UserController.java - 限流入口

```java
@RestController
public class UserController {

    @GetMapping("/user/local")
    @SentinelResource(
        value = "userLocalApi",
        blockHandlerClass = UserControllerBlockHandler.class,
        blockHandler = "userLocalBlock"
    )
    public Mono<String> userLocal() {
        return Mono.just("【用户服务】测试用户ID：001");
    }

    @GetMapping("/user/callOrder")
    @SentinelResource(
        value = "userCallOrderApi",
        blockHandlerClass = UserControllerBlockHandler.class,
        blockHandler = "userCallOrderBlock"
    )
    public Mono<String> callOrder() {
        return orderClient.getOrderInfo()
                .map(resp -> "【用户服务】远程调用订单结果：" + resp);
    }
}
```

**关键点**：
- `@SentinelResource(value = "资源名")`：标记需要保护的资源
- `blockHandlerClass`：指定降级方法所在的类
- `blockHandler`：指定降级方法名

#### 2. UserControllerBlockHandler.java - 降级处理类

```java
public class UserControllerBlockHandler {

    public static Mono<String> userLocalBlock(BlockException e) {
        return Mono.just("【接口限流】用户本地查询访问量过大，请稍后");
    }

    public static Mono<String> userCallOrderBlock(BlockException e) {
        return Mono.just("【接口限流】远程调用订单服务访问量过大，请稍后");
    }
}
```

**关键点**：
- 方法必须是 `static`
- 参数必须比原方法多一个 `BlockException`
- 返回值类型必须和原方法一致

#### 3. SentinelWebFluxConfig.java - 全局降级配置

```java
@Configuration
public class SentinelWebFluxConfig {

    @Bean
    public BlockRequestHandler sentinelBlockRequestHandler() {
        return (exchange, ex) -> {
            String msg = "请求被Sentinel流量限制：当前访问量超出阈值，请稍后重试";
            return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                    .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                    .bodyValue(msg);
        };
    }
}
```

**关键点**：
- `BlockRequestHandler`：全局兜底降级处理器
- 处理未配置 `@SentinelResource` 的接口被 URL 规则限流的情况
- 显式设置 `charset=UTF-8`，防止中文乱码

#### 4. application.yaml - Sentinel 配置

```yaml
spring:
  cloud:
    sentinel:
      transport:
        dashboard: localhost:8090
```

**关键点**：
- `dashboard`：Sentinel 控制台地址
- 启动后服务会自动连接控制台

### 3.2 限流规则配置（Sentinel 控制台）

在控制台配置限流规则：

| 配置项 | 值 | 说明 |
|--------|-----|------|
| 资源名 | `userLocalApi` | 必须与 `@SentinelResource` 的 `value` 一致 |
| 阈值类型 | QPS | 每秒请求数 |
| 阈值 | 1 | 每秒最多1次请求 |
| 流控模式 | 直接 | 针对当前资源限流 |

### 3.3 生效流程

```
请求到达 → Sentinel拦截
            ↓
    是否匹配 @SentinelResource 资源名？
            ↓
    ┌──────┴──────┐
    │             │
   是            否
    │             │
    ↓             ↓
调用 blockHandler  查看是否匹配URL规则
(自定义降级方法)     ↓
                    ┌──────┴──────┐
                    │             │
                   是            否
                    │             │
                    ↓             ↓
              调用 BlockRequestHandler   正常执行
              (全局兜底响应)
```

---

## 四、Maven 依赖

```xml
<!-- 远程调用 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-loadbalancer</artifactId>
</dependency>

<!-- Sentinel -->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
</dependency>

<!-- Nacos -->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
</dependency>
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
</dependency>
```

---

## 五、未来项目模板

### 5.1 需要创建的文件

| 文件 | 作用 | 是否必须 |
|------|------|----------|
| `XxxClient.java` | 声明式远程调用接口 | 是（有远程调用时） |
| `WebClientConfig.java` | WebClient + 代理工厂配置 | 是（有远程调用时） |
| `XxxController.java` | 控制器，加 @SentinelResource | 是 |
| `XxxControllerBlockHandler.java` | 降级处理类 | 是（有 @SentinelResource 时） |
| `SentinelWebFluxConfig.java` | 全局降级配置 | 建议保留 |

### 5.2 代码模板

#### 1. 远程调用接口

```java
@HttpExchange
public interface XxxClient {
    
    @GetExchange("/xxx/get")
    Mono<String> getXxxInfo();
    
    @PostExchange("/xxx/create")
    Mono<String> createXxx(@RequestBody XxxRequest request);
}
```

#### 2. WebClient 配置

```java
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient(ReactorLoadBalancerExchangeFilterFunction lbFilter) {
        return WebClient.builder()
                .filter(lbFilter)
                .baseUrl("lb://目标服务名")
                .build();
    }

    @Bean
    public XxxClient xxxClient(WebClient webClient) {
        return HttpServiceProxyFactory
                .builderFor(WebClientAdapter.create(webClient))
                .build()
                .createClient(XxxClient.class);
    }
}
```

#### 3. Controller + Sentinel

```java
@RestController
public class XxxController {

    private final XxxClient xxxClient;

    public XxxController(XxxClient xxxClient) {
        this.xxxClient = xxxClient;
    }

    @GetMapping("/xxx/local")
    @SentinelResource(value = "xxxLocalApi", blockHandlerClass = XxxControllerBlockHandler.class, blockHandler = "xxxLocalBlock")
    public Mono<String> local() {
        return Mono.just("本地响应");
    }

    @GetMapping("/xxx/remote")
    @SentinelResource(value = "xxxRemoteApi", blockHandlerClass = XxxControllerBlockHandler.class, blockHandler = "xxxRemoteBlock")
    public Mono<String> remote() {
        return xxxClient.getXxxInfo();
    }
}
```

#### 4. 降级处理类

```java
public class XxxControllerBlockHandler {

    public static Mono<String> xxxLocalBlock(BlockException e) {
        return Mono.just("【限流】本地接口访问量过大");
    }

    public static Mono<String> xxxRemoteBlock(BlockException e) {
        return Mono.just("【限流】远程调用访问量过大");
    }
}
```

#### 5. Sentinel 配置

```java
@Configuration
public class SentinelWebFluxConfig {

    @Bean
    public BlockRequestHandler sentinelBlockRequestHandler() {
        return (exchange, ex) -> {
            String msg = "请求被Sentinel流量限制：当前访问量超出阈值，请稍后重试";
            return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                    .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                    .bodyValue(msg);
        };
    }
}
```

#### 6. application.yaml

```yaml
spring:
  application:
    name: xxx-service
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
    sentinel:
      transport:
        dashboard: localhost:8090
server:
  port: 8080
```

---

## 六、核心知识点总结

| 知识点 | 说明 |
|--------|------|
| **@HttpExchange** | Spring 6+ 声明式 HTTP 客户端注解，替代 OpenFeign |
| **WebClient** | 异步非阻塞 HTTP 客户端，Spring WebFlux 默认推荐 |
| **HttpServiceProxyFactory** | 根据接口注解生成代理类，无需手写实现 |
| **负载均衡** | 通过 `ReactorLoadBalancerExchangeFilterFunction` 实现 |
| **@SentinelResource** | Sentinel 限流注解，标记需要保护的资源 |
| **blockHandler** | 限流触发时的降级方法，参数多一个 BlockException |
| **blockHandlerClass** | 降级方法所在的类，方法必须是 static |
| **BlockRequestHandler** | 全局兜底降级处理器，处理未配置注解的接口限流 |
| **资源名** | 控制台配置规则时必须与 `@SentinelResource` 的 `value` 一致 |

---

## 七、最佳实践

1. **限流放在入口层**：`@SentinelResource` 加在 Controller 方法上，不在底层 Client 上加
2. **降级方法统一管理**：使用 `blockHandlerClass` 外部类方式，保持代码整洁
3. **路径完全对应**：`@GetExchange` 路径必须与目标服务的 `@GetMapping` 一致
4. **中文编码保障**：`BlockRequestHandler` 显式设置 `charset=UTF-8`
5. **启动顺序**：先启动 Nacos → Sentinel Dashboard → 各微服务