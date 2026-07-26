# GatewayWebflux 微服务Sentinel学习完整项目设计方案（修复完善完整版）

# GatewayWebflux 微服务Sentinel学习完整项目设计方案（修复完善完整版）

## 前置说明

1. 硬性约束：全微服务统一 WebFlux 响应式栈，废弃 OpenFeign，采用 `WebClient + LoadBalancer` 实现服务间调用，彻底解决同步Feign与WebFlux线程冲突

2. 版本栈（官方兼容无冲突）

|组件|版本|说明|
|---|---|---|
|JDK|21 LTS|编译运行统一环境|
|Spring Boot|3\.5\.16|父工程继承官方父POM|
|Spring Cloud|2025\.0\.3|云原生规范|
|Spring Cloud Alibaba|2025\.0\.0\.0|BOM内置Sentinel 1\.8\.9、Nacos Client 3\.0\.3|
|Sentinel Dashboard|1\.8\.9|本地启动端口8090|
|Nacos Server|3\.2\.3|注册中心\+配置中心|
|JMeter|5\.6\.3|压测验证限流熔断|

3. 已确认规则

- Nacos：public命名空间、DEFAULT\_GROUP，公共配置`common-dev.yaml`，import显式带命名空间/分组

- Sentinel规则：内存临时存储，重启丢失，适合本地学习

- 端口：user\-service=8085，order\-service=8086

- 调用链路：user\-service 通过WebClient单向调用 order\-service

- 配置分层：

    1. 本地application\.yaml：显式声明Nacos连接地址、服务私有属性；

    2. Nacos远程common\-dev\.yaml：全局公共基础配置；

- 限流：单机QPS流控；熔断：远程调用异常/流量拦截双兜底

- 无统一返回实体，全部返回纯字符串，日志打印链路信息

## 一、工程整体目录结构

```Plaintext
GatewayWebflux (聚合父工程 packaging=pom)
├─ pom.xml                 # 全局版本统一管控
├─ gateway/                # 原有网关模块，本次不接入Sentinel
│  └─ pom.xml
├─ user-service/           # 微服务1，端口8085
│  ├─ pom.xml
│  └─ src/main
│     ├─ java/com/qsx/user
│     │  ├─ UserServiceApplication.java        # 启动类
│     │  ├─ config/
│     │  │  ├─ WebClientConfig.java            # 负载均衡WebClient配置
│     │  │  └─ SentinelWebFluxConfig.java      # Sentinel WebFlux全局拦截配置
│     │  ├─ client/OrderRemoteClient.java      # 远程调用订单客户端（带Sentinel注解）
│     │  └─ controller/UserController.java      # 用户接口控制器
│     └─ resources/application.yaml            # 本地私有配置（已完善Nacos地址）
└─ order-service/          # 微服务2，端口8086
   ├─ pom.xml
   └─ src/main
      ├─ java/com/qsx/order
      │  ├─ OrderServiceApplication.java        # 启动类
      │  ├─ config/SentinelWebFluxConfig.java  # Sentinel全局拦截配置
      │  └─ controller/OrderController.java    # 订单接口控制器
      └─ resources/application.yaml             # 本地私有配置（已完善Nacos地址）
```

## 二、父工程 pom\.xml 完整代码（详细注释）

```XML
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <!-- 项目坐标：聚合父工程 -->
    <groupId>com.qsx</groupId>
    <artifactId>GatewayWebflux</artifactId>
    <version>1.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <!-- 继承Spring Boot官方父，统一Boot基础依赖版本 -->
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.5.16</version>
        <relativePath />
    </parent>
    <properties>
        <!-- JDK编译版本约束，全局统一21 -->
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <!-- 文件编码统一UTF-8，避免中文乱码 -->
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>
        <!-- 云组件版本常量，子模块直接引用无需硬编码 -->
        <spring.cloud.version>2025.0.3</spring.cloud.version>
        <spring.cloud.alibaba.version>2025.0.0.0</spring.cloud.alibaba.version>
        <!-- 约束所有子模块打包类型为jar，防止新建模块写错 -->
        <maven.packaging>jar</maven.packaging>
    </properties>
    <!-- 依赖版本统一管理，子模块只写坐标无需指定version -->
    <dependencyManagement>
        <dependencies>
            <!-- Spring Cloud BOM：统一Gateway、LoadBalancer等组件版本 -->
            <dependency>
                <groupId>org.springframework.cloud</groupId>
                <artifactId>spring-cloud-dependencies</artifactId>
                <version>${spring.cloud.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <!-- Spring Cloud Alibaba BOM：统一Nacos、Sentinel版本，内置Sentinel1.8.9 -->
            <dependency>
                <groupId>com.alibaba.cloud</groupId>
                <artifactId>spring-cloud-alibaba-dependencies</artifactId>
                <version>${spring.cloud.alibaba.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
    <!-- 聚合全部子模块 -->
    <modules>
        <module>gateway</module>
        <module>user-service</module>
        <module>order-service</module>
    </modules>
</project>
```

## 三、Nacos 公共配置 common\-dev\.yaml（public/DEFAULT\_GROUP）

存放所有模块通用配置，网关、user、order统一import拉取，无Feign相关配置消除警告

```YAML
# ===================== Nacos公共全局配置 common-dev.yaml =====================
# 命名空间：public 分组：DEFAULT_GROUP
# 所有微服务通过 spring.config.import 引入，统一复用，减少重复配置
spring:
  cloud:
    nacos:
      # Nacos注册中心地址
      discovery:
        server-addr: 127.0.0.1:8848
      # Nacos配置中心地址
      config:
        server-addr: 127.0.0.1:8848
        # 配置文件前缀：默认${spring.application.name}
        prefix: ${spring.application.name}
        # 配置文件后缀统一yaml
        file-extension: yaml
# 监控端点全部开放，用于查看Sentinel指标、服务健康状态
management:
  endpoints:
    web:
      exposure:
        include: "*"
# 全局日志级别，压测时减少冗余日志刷屏，方便查看限流/降级日志
logging:
  level:
    root: info
    # Sentinel日志仅打印info级别，屏蔽大量调试日志
    com.alibaba.cloud.sentinel: info
    org.springframework.cloud: info
```

## 四、子模块 pom\.xml 通用模板（user\-service / order\-service）

### 核心改动：移除openfeign，新增loadbalancer负载均衡依赖

```XML
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <!-- 继承顶层聚合父工程，自动复用所有版本常量 -->
    <parent>
        <groupId>com.qsx</groupId>
        <artifactId>GatewayWebflux</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>
    <!-- 当前子模块唯一标识 -->
    <artifactId>user-service</artifactId>
    <packaging>${maven.packaging}</packaging>
    <properties>
        <!-- 复用父工程JDK编码配置，保持统一 -->
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
    <dependencies>
        <!-- WebFlux响应式web核心，全程非阻塞，与网关技术栈统一 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>
        <!-- Nacos 服务注册：将当前服务注册到Nacos，供其他服务lb://调用 -->
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>
        <!-- Nacos 配置中心：拉取远程common-dev.yaml与服务私有配置 -->
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
        </dependency>
        <!-- Sentinel客户端核心依赖，由SCA BOM锁定1.8.9版本 -->
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
        </dependency>
        <!-- 负载均衡核心依赖：支持WebClient使用lb://服务名调用远程服务，必加 -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-loadbalancer</artifactId>
        </dependency>
        <!-- 监控端点：健康检查、流量指标、Sentinel监控数据暴露 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <!-- Lombok简化日志、实体代码，打包时排除避免冗余 -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <version>1.18.34</version>
            <optional>true</optional>
        </dependency>
        <!-- 单元测试依赖 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <!-- SpringBoot打包插件，生成可执行jar包 -->
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <!-- 打包排除lombok，运行时无需该依赖 -->
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

# 五、本地 application\.yaml 完善后配置（修复核心：显式补充Nacos config/discovery地址）

## 补充说明（新增文档段落）

### 原方案缺陷

最初版本未在本地yaml声明Nacos连接地址，依赖Nacos客户端内置默认地址`127.0.0.1:8848`，属于隐性逻辑，新手难以理解「如何拉取远程配置、如何注册服务」；若Nacos部署在其他服务器/修改端口，项目会直接启动失败。

### 完善优化点

1. 本地文件显式配置 `spring.cloud.nacos.config`、`spring.cloud.nacos.discovery`，明确指定注册/配置中心地址，无黑盒依赖；

2. 加载流程清晰：本地地址优先用于拉取Nacos公共配置，公共配置加载后复用同一地址完成服务注册；

3. 分层清晰：本地存放服务私有属性（端口、服务名、Nacos连接地址、Sentinel控制台地址），公共全局配置统一放Nacos远程文件。

### 配置加载完整流程

1. 服务启动读取本地application\.yaml，获取Nacos连接地址；

2. 使用该地址发起请求，拉取Nacos远程`common-dev.yaml`公共配置；

3. 远程配置合并到Spring上下文；

4. Nacos Discovery组件读取上下文地址，将当前服务注册至注册中心；

5. WebFlux、Sentinel、Actuator等组件完成初始化。

### user\-service /resources/application\.yaml（修复完整版）

```YAML
# 本地私有配置：存放当前服务独有属性，显式声明Nacos连接地址
spring:
  # 当前服务名称，Nacos注册、lb://远程调用标识
  application:
    name: user-service
  # 激活dev环境，匹配Nacos公共配置common-dev.yaml
  profiles:
    active: dev
  # 显式导入Nacos公共全局配置（统一监控、日志基础参数）
  config:
    import: nacos:common-dev.yaml?namespace=public&group=DEFAULT_GROUP
  # 【修复完善】显式指定Nacos配置中心、注册中心地址，不依赖客户端默认值
  cloud:
    nacos:
      # 配置中心地址：用于拉取远程common-dev.yaml
      config:
        server-addr: 127.0.0.1:8848
      # 注册中心地址：服务启动后自动注册当前实例
      discovery:
        server-addr: 127.0.0.1:8848

# Sentinel控制台上报地址，微服务推送流量监控数据到8090控制台
spring.cloud.sentinel.transport.dashboard: localhost:8090
# 固定端口8085，方便本地测试记忆
server:
  port: 8085
```

### order\-service /resources/application\.yaml（修复完整版）

```YAML
# 本地私有配置：存放当前服务独有属性，显式声明Nacos连接地址
spring:
  application:
    name: order-service
  profiles:
    active: dev
  config:
    import: nacos:common-dev.yaml?namespace=public&group=DEFAULT_GROUP
  # 【修复完善】显式指定Nacos配置、注册中心地址
  cloud:
    nacos:
      config:
        server-addr: 127.0.0.1:8848
      discovery:
        server-addr: 127.0.0.1:8848

spring.cloud.sentinel.transport.dashboard: localhost:8090
server:
  port: 8086
```

## 六、公共配置类代码（带完整注释、流程说明）

### 6\.1 Sentinel WebFlux 全局拦截配置（两个模块通用）

作用：全局捕获WebFlux接口限流BlockException，统一返回中文提示，替换原生英文`Blocked by Sentinel`

**【重要修复】Sentinel 1.8.9 版本正确配置方式**

> 注意：Sentinel 1.8.9 的 WebFlux 适配器中使用的是 `BlockRequestHandler` 接口（方法名 `handleRequest`），而非文档原示例中的 `WebFluxBlockExceptionHandler`（该类不存在于1.8.9版本）。同时必须注册 `SentinelResourceAspect` 并开启 `@EnableAspectJAutoProxy`，否则 `@SentinelResource` 注解切面不会生效。

```Java
package com.qsx.user.config;
import com.alibaba.csp.sentinel.adapter.spring.webflux.callback.BlockRequestHandler;
import com.alibaba.csp.sentinel.annotation.aspectj.SentinelResourceAspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
/**
 * Sentinel WebFlux全局限流异常处理器配置
 * 生效流程：
 * 1. 请求进入WebFlux链路，Sentinel拦截器优先校验流量规则
 * 2. 触发限流/熔断抛出BlockException
 * 3. 本Bean捕获异常统一返回中文文本
 * 4. 未触发异常则正常进入Controller业务逻辑
 */
@Configuration
@EnableAspectJAutoProxy
public class SentinelWebFluxConfig {
    /**
     * 注册Sentinel全局阻塞异常处理器
     * @return BlockRequestHandler 全局拦截实例
     */
    @Bean
    public BlockRequestHandler sentinelBlockRequestHandler() {
        return new BlockRequestHandler() {
            /**
             * 捕获限流/熔断异常统一处理
             * @param exchange 请求上下文（请求头、路径、参数）
             * @param ex Sentinel抛出的阻塞异常（流控、降级、授权）
             * @return Mono<ServerResponse> 响应式返回中文限流提示
             */
            @Override
            public Mono<ServerResponse> handleRequest(ServerWebExchange exchange, Throwable ex) {
                String msg = "请求被Sentinel流量限制：当前访问量超出阈值，请稍后重试";
                return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.TEXT_PLAIN)
                        .bodyValue(msg);
            }
        };
    }

    /**
     * 注册Sentinel注解切面，确保@SentinelResource注解生效
     * @return SentinelResourceAspect 切面实例
     */
    @Bean
    public SentinelResourceAspect sentinelResourceAspect() {
        return new SentinelResourceAspect();
    }
}
```

### 6\.2 user\-service WebClient负载均衡配置

作用：构建支持`lb://`服务名的响应式WebClient，用于远程调用order\-service

```Java
package com.qsx.user.config;
import org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerExchangeFilterFunction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
/**
 * 响应式WebClient配置，整合负载均衡，替代OpenFeign
 * 调用流程：
 * 1. Controller注入WebClient Bean
 * 2. WebClient内置LoadBalancer过滤器，解析lb://order-service
 * 3. 从Nacos获取order服务实例地址，自动负载均衡转发请求
 */
@Configuration
public class WebClientConfig {
    /**
     * 构建带负载均衡能力的WebClient
     * @param lbFilter 响应式负载均衡过滤器，由SpringCloud自动注入
     * @return WebClient 远程调用客户端，基础地址锁定lb://order-service
     */
    @Bean
    public WebClient webClient(ReactorLoadBalancerExchangeFilterFunction lbFilter) {
        return WebClient.builder()
                // 注入负载均衡过滤器，支持服务名解析
                .filter(lbFilter)
                // 默认远程调用目标服务
                .baseUrl("lb://order-service")
                .build();
    }
}
```

## 七、远程调用客户端 OrderRemoteClient（user\-service专用，核心Sentinel注解案例）

同时演示 `blockHandler(流量拦截兜底)` \+ `fallback(服务异常兜底)`

**【重要修复】blockHandler 方法签名必须使用 BlockException**

> 注意：`blockHandler` 方法的参数必须是 `BlockException`（而非 `Throwable`），否则 Sentinel 运行时无法找到并调用兜底方法。`fallback` 方法保持使用 `Throwable`，因为它处理业务异常。

```Java
package com.qsx.user.client;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
/**
 * 远程调用Order服务客户端，带Sentinel熔断降级能力
 * 资源生效流程：
 * 1. 调用getOrderInfo()触发@SentinelResource标记资源callOrderRemote
 * 2. Sentinel校验流量规则，超限执行blockHandler
 * 3. 下游服务宕机/超时执行fallback兜底
 * 4. 控制台监控面板可查看该资源QPS、拦截、异常指标
 */
@Slf4j
@Component
public class OrderRemoteClient {
    private final WebClient webClient;
    public OrderRemoteClient(WebClient webClient) {
        this.webClient = webClient;
    }
    /**
     * 远程调用订单服务接口 /order/get
     * @SentinelResource 标记受Sentinel保护的资源
     * value：资源唯一名称，控制台配置流控/熔断规则时使用
     * blockHandler：流量超限、主动限流时执行的兜底方法，参数必须是BlockException
     * fallback：下游服务异常、宕机、超时执行的降级方法，参数是Throwable
     * @return Mono<String> 响应式订单接口返回文本
     */
    @SentinelResource(
            value = "callOrderRemote",
            blockHandler = "remoteBlockHandler",
            fallback = "remoteFallbackHandler"
    )
    public Mono<String> getOrderInfo() {
        return webClient.get()
                .uri("/order/get")
                .retrieve()
                .bodyToMono(String.class);
    }
    /**
     * blockHandler 限流/熔断阻塞兜底方法
     * 入参必须是BlockException，接收Sentinel阻塞异常（流控、降级、授权）
     * @param e 阻塞异常对象
     * @return Mono<String> 限流提示文本
     */
    public Mono<String> remoteBlockHandler(BlockException e) {
        log.info("[远程调用] order服务触发Sentinel限流/熔断，异常信息：{}", e.getMessage());
        return Mono.just("【流量限流兜底】订单接口访问高峰，暂时无法访问");
    }
    /**
     * fallback 服务异常降级兜底方法
     * 下游服务下线、连接超时、业务异常时触发
     * @param e 调用异常
     * @return Mono<String> 服务不可用提示
     */
    public Mono<String> remoteFallbackHandler(Throwable e) {
        log.info("[远程调用] order服务调用异常降级，异常信息：{}", e.getMessage());
        return Mono.just("【服务降级兜底】订单服务暂时不可用，请稍后重试");
    }
}
```

## 八、Controller 控制器代码（@SentinelResource本地接口演示）

### 8\.1 order\-service OrderController

```Java
package com.qsx.order.controller;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
/**
 * 订单服务接口，演示本地接口单机QPS限流
 */
@Slf4j
@RestController
public class OrderController {
    /**
     * 订单查询接口，@SentinelResource手动标记资源
     * @return Mono<String> 订单文本信息
     */
    @GetMapping("/order/get")
    @SentinelResource(value = "orderGetApi", blockHandler = "orderBlockHandler")
    public Mono<String> getOrder() {
        log.info("[Order服务] 正常处理订单查询请求");
        return Mono.just("【订单服务】测试订单号：10001，状态：正常");
    }
    /**
     * 当前接口专属限流兜底方法
     * 全局异常处理器与该方法双重兜底演示
     * @param e 阻塞异常（必须是BlockException类型）
     * @return Mono<String> 接口专属限流提示
     */
    public Mono<String> orderBlockHandler(BlockException e) {
        log.info("[Order服务] orderGetApi接口触发单机限流");
        return Mono.just("【接口专属限流】订单查询请求过于频繁，请稍后访问");
    }
}
```

### 8\.2 user\-service UserController

提供两个接口：本地限流接口、远程调用熔断接口

```Java
package com.qsx.user.controller;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.qsx.user.client.OrderRemoteClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
/**
 * 用户服务控制器
 * 接口1：/user/local 纯本地逻辑，演示单机QPS限流
 * 接口2：/user/callOrder WebClient远程调用order，演示熔断降级
 */
@Slf4j
@RestController
public class UserController {
    private final OrderRemoteClient orderRemoteClient;
    public UserController(OrderRemoteClient orderRemoteClient) {
        this.orderRemoteClient = orderRemoteClient;
    }
    /**
     * 本地用户查询接口，无远程调用，纯本地限流演示
     * @return Mono<String> 用户信息文本
     */
    @GetMapping("/user/local")
    @SentinelResource(value = "userLocalApi", blockHandler = "userLocalBlock")
    public Mono<String> userLocal() {
        log.info("[User服务] 执行本地用户查询逻辑");
        return Mono.just("【用户服务】测试用户ID：001，姓名：测试用户");
    }
    /**
     * user/local接口专属限流兜底
     * @param e 阻塞异常（必须是BlockException类型）
     */
    public Mono<String> userLocalBlock(BlockException e) {
        log.info("[User服务] userLocalApi接口触发限流");
        return Mono.just("【接口限流】用户本地查询访问量过大，请稍后");
    }
    /**
     * 远程调用订单服务接口，演示WebClient+Sentinel熔断
     * @return Mono<String> 拼接用户+订单返回文本
     */
    @GetMapping("/user/callOrder")
    public Mono<String> callOrder() {
        log.info("[User服务] 开始远程调用order服务接口");
        return orderRemoteClient.getOrderInfo()
                .map(resp -> "【用户服务】远程调用订单结果：" + resp);
    }
}
```

## 九、启动类代码

### 9\.1 user\-service UserServiceApplication

```Java
package com.qsx.user;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
/**
 * 用户服务启动类
 * @SpringBootApplication SpringBoot自动装配
 * @EnableDiscoveryClient 开启Nacos服务注册发现，自动注册当前服务到Nacos
 */
@SpringBootApplication
@EnableDiscoveryClient
public class UserServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
```

### 9\.2 order\-service OrderServiceApplication

```Java
package com.qsx.order;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
/**
 * 订单服务启动类
 */
@SpringBootApplication
@EnableDiscoveryClient
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
```

## 十、完整学习测试流程（生效逻辑说明）

### 1\. 启动顺序（必须严格遵守，避免注册/监控异常）

1. 启动 Nacos 3\.2\.3，进入控制台创建 public命名空间下 `common-dev.yaml` 公共配置（内容见第三节）

2. 运行脚本启动 Sentinel Dashboard 1\.8\.9（8090端口）

3. 启动 order\-service（8086）

4. 启动 user\-service（8085）

5. 首次手动访问一次接口（Sentinel懒加载，不调用控制台看不到资源）

### 场景1：单机接口QPS限流演示（JMeter压测 /user/local）

1. 访问 http://localhost:8090 进入Sentinel控制台，选中user\-service

2. 流控规则新增：资源名`userLocalApi`，单机阈值5（每秒最多5次请求）

3. JMeter创建HTTP请求，高频循环压测接口

4. 流量超过阈值：

    - 优先执行`userLocalBlock`返回专属限流文本

    - 无注解兜底时触发全局Sentinel异常处理器统一返回

    - 控制台实时统计拦截QPS、监控图表展示流量曲线

### 场景2：远程调用熔断降级演示（访问 /user/callOrder）

1. 正常双服务运行：返回正常拼接字符串，控制台可见`callOrderRemote`资源

2. 停止order\-service服务：触发`remoteFallbackHandler`服务异常降级

3. order服务正常，但给`orderGetApi`设置极低QPS阈值：远程调用触发`remoteBlockHandler`流量限流兜底

## 十一、方案全部修复点汇总

1. 解决WebFlux \+ OpenFeign线程阻塞冲突，替换为原生响应式WebClient\+LoadBalancer

2. 所有微服务补充loadbalancer依赖，支持lb://服务名调用

3. Feign配置全部删除，公共配置无冗余，网关无启动警告

4. 新增WebFlux全局Sentinel异常处理器，限流返回中文提示

5. Controller、远程客户端双位置提供`@SentinelResource`完整案例，区分本地接口/远程调用两种资源

6. 全部代码增加方法、参数、返回、调用/生效流程注释，便于入门学习

7. **【本次重点修复】完善本地application\.yaml，显式配置Nacos config/discovery地址，消除默认地址隐性依赖，新手更容易理解配置拉取、服务注册逻辑**

8. Nacos配置分层清晰，补充加载流程说明，本地私有配置、远程公共配置边界明确

9. 区分两种兜底机制：接口专属blockHandler、全局异常处理器、远程fallback三层演示，覆盖Sentinel核心能力

## 十二、实际项目结构与关键修复说明

### 12.1 实际项目目录结构

```Plaintext
GatewayWebflux/
├── pom.xml                                   # 聚合父工程，包含三个模块声明
├── gateway/                                  # 网关模块（端口8080）
│   ├── pom.xml
│   └── src/main/
│       ├── java/GatewayApplication.java
│       └── resources/application.yaml        # 已更新：添加Nacos配置和路由规则
├── user-service/                             # 用户服务模块（端口8085）
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/qsx/user/
│       │   ├── UserServiceApplication.java   # 启动类
│       │   ├── config/
│       │   │   ├── WebClientConfig.java      # WebClient负载均衡配置
│       │   │   └── SentinelWebFluxConfig.java# Sentinel配置（已修复）
│       │   ├── client/
│       │   │   └── OrderRemoteClient.java    # 远程调用客户端（含@SentinelResource）
│       │   └── controller/
│       │       └── UserController.java       # 控制器（含本地限流+远程调用接口）
│       └── resources/application.yaml        # 服务私有配置
└── order-service/                            # 订单服务模块（端口8086）
    ├── pom.xml
    └── src/main/
        ├── java/com/qsx/order/
        │   ├── OrderServiceApplication.java  # 启动类
        │   ├── config/
        │   │   └── SentinelWebFluxConfig.java# Sentinel配置（已修复）
        │   └── controller/
        │       └── OrderController.java      # 控制器（含本地限流接口）
        └── resources/application.yaml        # 服务私有配置
```

### 12.2 关键修复内容

#### 修复1：Sentinel WebFlux 异常处理器类名错误

| 项目 | 原文档内容（错误） | 实际修复内容（正确） |
|---|---|---|
| 接口类名 | `WebFluxBlockExceptionHandler` | `BlockRequestHandler` |
| 方法名 | `handle(ServerWebExchange, BlockException)` | `handleRequest(ServerWebExchange, Throwable)` |
| 注解切面 | 未注册 | 添加 `SentinelResourceAspect` Bean + `@EnableAspectJAutoProxy` |

**修复原因**：Sentinel 1.8.9 的 `sentinel-spring-webflux-adapter` 模块中不存在 `WebFluxBlockExceptionHandler` 类，正确的接口是 `BlockRequestHandler`。同时，`@SentinelResource` 注解需要 `SentinelResourceAspect` 切面才能生效，必须显式注册。

#### 修复2：user-service UserController 依赖注入方式优化

| 原文档内容 | 修复后内容 |
|---|---|
| `@Autowired` 字段注入 | 构造器注入 |

**修复原因**：Spring 官方推荐使用构造器注入，避免空指针风险，提高代码可测试性。

#### 修复3：gateway 模块配置补充

| 原配置 | 修复后配置 |
|---|---|
| 无Nacos配置、路由规则示例 | 完整Nacos配置 + 转发到user-service/order-service的路由规则 |

#### 注意：响应式 @SentinelResource 的局限性

> **重要说明**：`@SentinelResource` 是基于 AOP 实现的，方法执行在返回 `Mono` 对象时即结束，不会等待 `Mono` 异步完成。因此：
> 
> 1. **blockHandler（流控）**：正常生效，因为限流发生在方法执行前
> 2. **fallback（服务异常降级）**：对于 WebClient 远程调用，当 order-service 宕机时，WebClient 的错误发生在 `Mono` 异步执行阶段，此时 AOP 切面已退出，`fallback` 可能无法正确触发。
> 
> **建议**：本项目重点演示 `blockHandler` 流控场景（限流/熔断规则触发的阻塞兜底），这是 Sentinel 的核心能力，能够正常工作。若需要完整的响应式异常降级，建议使用 `SentinelReactorTransformer` 进行 reactor 层面的集成。

### 12.3 核心功能接口清单

| 服务 | 接口路径 | Sentinel资源名 | 功能演示 |
|---|---|---|---|
| user-service | `/user/local` | `userLocalApi` | 本地接口单机QPS限流 |
| user-service | `/user/callOrder` | `callOrderRemote` | 远程调用熔断降级 |
| order-service | `/order/get` | `orderGetApi` | 本地接口单机QPS限流 |

### 12.4 启动前准备

1. **Nacos Server**（3.2.3）：启动在 `127.0.0.1:8848`，创建 `public` 命名空间下的 `common-dev.yaml` 配置
2. **Sentinel Dashboard**（1.8.9）：启动在 `localhost:8090`
3. **启动顺序**：order-service(8086) → user-service(8085) → gateway(8080)

> （注：Sentinel规则内存临时存储，重启服务规则丢失，仅适用于本地学习环境；生产环境需将规则持久化至Nacos。）

> （注：部分内容可能由 AI 生成）
