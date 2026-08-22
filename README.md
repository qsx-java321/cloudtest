# GatewayWebflux（Spring Cloud Gateway + Sentinel 学习项目）

一个基于 **Spring Cloud Gateway（WebFlux 版）+ Sentinel 限流 + Nacos 注册/配置中心** 的微服务学习项目，采用**混合技术栈**：网关保持响应式 WebFlux，业务模块使用主流的 Spring MVC + OpenFeign，演示了网关路由、声明式远程调用（OpenFeign）与 Sentinel 三层限流体系。

---

## 一、技术栈与版本

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 21 | 编译与运行目标 |
| Spring Boot | 3.5.16 | 由父 POM 统一管理 |
| Spring Cloud | 2025.0.3 | 通过 `spring-cloud-dependencies` BOM 导入 |
| Spring Cloud Alibaba | 2025.0.0.0 | 通过 `spring-cloud-alibaba-dependencies` BOM 导入 |
| Spring Cloud Gateway | 4.3.5 | 网关，`gateway-server-webflux` starter |
| Spring Cloud LoadBalancer | 4.3.3 | 客户端负载均衡（替代 Ribbon） |
| OpenFeign | 4.3.5 | 业务模块声明式远程调用 |
| Sentinel 客户端 | 1.8.9 | `sentinel-core` / `sentinel-transport-common` |
| Sentinel 控制台 | 1.8.9 | 网页控制台，端口 8090 |
| Nacos 客户端 | 3.0.3 | 由 SCA BOM 管理 |
| Nacos Server | 3.2.3 | 注册中心 + 配置中心，网页控制台端口 18080，gRPC 端口 8848 |

> **版本对齐说明**
> - Sentinel 客户端与控制台同为 1.8.9，同版本才能正常上报心跳并同步规则。
> - Nacos 3.x 服务端兼容 3.x / 2.x 客户端，走 gRPC 协议（8848/9848），配置中的 `127.0.0.1:8848` 为服务端 gRPC 端口，网页控制台 18080 只用于人工管理。
> - 所有依赖版本均由父 POM 的 BOM 统一管理，子模块不显式声明版本号，避免版本冲突。

### 模块依赖一览

| 模块 | 核心依赖 |
|------|----------|
| gateway | `spring-cloud-starter-gateway-server-webflux`、loadbalancer、nacos-discovery、sentinel、actuator |
| user-service | `spring-boot-starter-web`、openfeign、loadbalancer、nacos-discovery、sentinel、actuator |
| order-service | `spring-boot-starter-web`、loadbalancer、nacos-discovery、sentinel、actuator |

> **技术栈边界**：网关（WebFlux）与业务模块（MVC）是**独立进程**，互不影响；同一进程内不混用 `web` 与 `webflux` 两个 starter，避免 Boot 强制切换 Web 类型。

---

## 二、项目结构与模块职责

```
cloud-gateway/
├── pom.xml                       # 聚合父工程，仅导入两个 BOM，声明三个子模块
├── gateway/                      # API 网关，端口 8080
├── user-service/                 # 用户服务，端口 8085，含远程调用示例
├── order-service/                # 订单服务，端口 8086，被调方
└── README.md
```

### 1. gateway —— API 网关

- **职责**：统一入口，按路径将请求路由到下游微服务。
- **路由配置**（`application.yaml`）：
  - `/user/**` → `lb://user-service`
  - `/order/**` → `lb://order-service`
  - 使用新版配置前缀 `spring.cloud.gateway.server.webflux.routes`（对应 Gateway 5.x / 4.x 新写法，旧前缀 `spring.cloud.gateway.routes` 已弃用）
- **负载均衡**：`lb://` 协议 + `spring-cloud-starter-loadbalancer`（响应式 `ReactorLoadBalancer`），从 Nacos 发现实例。
- **Sentinel 网关流控**：已启用 `spring.cloud.sentinel.filter.enabled: true`，可通过控制台对网关进行流控。
- **全局兜底**：`SentinelWebFluxConfig` 注册 `BlockRequestHandler`，限流时返回 429 + 自定义中文提示（UTF-8）。
- **技术形态**：网关为 WebFlux（`Mono`/`Flux`），仅负责路由转发，不写业务逻辑，不使用阻塞式调用。

### 2. user-service —— 用户服务

- **职责**：提供用户相关接口，并演示 **OpenFeign 声明式远程调用**下游订单服务。
- **技术栈**：Spring MVC（`spring-boot-starter-web`）。
- **对外接口**：
  - `GET /user/local`：本地查询，Sentinel 资源名 `userLocalApi`
  - `GET /user/callOrder`：远程调用订单服务，Sentinel 资源名 `userCallOrderApi`
- **远程调用方式**：OpenFeign + Spring Cloud LoadBalancer：
  - `OrderClient`：`@FeignClient(name = "order-service", fallbackFactory = OrderClientFallbackFactory.class)`，方法 `@GetMapping("/order/get")` 返回 `String`
  - `OrderClientFallbackFactory` + `OrderClientFallback`：SentinelFeign 降级（需 `feign.sentinel.enabled: true`），order 服务不可用或限流时返回兜底文案
  - 主类 `@EnableFeignClients` 扫描并生成代理；`lb://` 解析由 `FeignBlockingLoadBalancerClient` 完成
- **Sentinel**：两个接口均用 `@SentinelResource` 保护，降级方法在 `UserControllerBlockHandler` 中；URL 层兜底由 `SentinelWebMvcInterceptorConfig`（webmvc v6x 适配）提供。

### 3. order-service —— 订单服务

- **职责**：提供订单查询接口，作为远程调用的**被调方**。
- **技术栈**：Spring MVC（`spring-boot-starter-web`）。
- **对外接口**：`GET /order/get`，Sentinel 资源名 `orderGetApi`
- **Sentinel**：`@SentinelResource` 保护，降级方法在 `OrderControllerBlockHandler` 中；URL 层兜底由 `SentinelWebMvcInterceptorConfig`（webmvc v6x 适配）提供。

---

## 三、核心业务流程

### 1. 整体请求链路

```
外部请求
    │
    ▼
Gateway (8080)  ── /user/** ──► user-service (8085)
    │                  │
    │                  └── /user/callOrder ──► OpenFeign(lb://order-service) ──► order-service (8086) /order/get
    │
    └── /order/** ──► order-service (8086)
```

### 2. 远程调用流程（user → order）

```
调用 orderClient.getOrderInfo()
  → @FeignClient 代理（SentinelFeign）拦截
  → FeignBlockingLoadBalancerClient 解析 lb://order-service，经 Spring Cloud LoadBalancer 从 Nacos 选择实例
  → 发起 HTTP 请求到 /order/get
  → 返回 String 响应；若目标不可用/限流则走 OrderClientFallback 降级
```

关键约束：Feign 接口方法上的 `@GetMapping` 路径必须与目标服务 `@GetMapping` 完全一致。

### 3. Sentinel 三层限流体系

| 层级 | 机制 | 触发后响应 |
|------|------|-----------|
| ① 注解层 | 接口上的 `@SentinelResource` + 静态 `blockHandler` | 429 + 各接口专属文案 |
| ② URL 层兜底 | 每服务注册的 `SentinelWebMvcInterceptorConfig`（webmvc v6x 拦截器 + `BlockExceptionHandler`） | 429 + 全局文案（UTF-8 防乱码） |
| ③ 网关层 | gateway 开启 `sentinel.filter.enabled` | 429 + 网关兜底文案 |

控制台配置限流规则时，**资源名必须与 `@SentinelResource` 的 `value` 一致**：`userLocalApi`、`userCallOrderApi`、`orderGetApi`。

> **双套 Sentinel 拦截说明**：业务模块已注册 `SentinelWebInterceptor`（URL 层），因此在 `application.yaml` 中关闭了 SCA 默认的 servlet 过滤器 `spring.cloud.sentinel.filter.enabled: false`，避免两套机制重复统计同一资源。网关模块则保持 `filter.enabled: true`。

---

## 四、配置说明

三个服务**不依赖 Nacos 配置中心**，所有配置写死在本地 `application.yaml`，仅使用 Nacos 做**服务注册发现**（`nacos-discovery`）。因此无需在 Nacos 预置任何 Data ID。

| 服务 | 端口 | 注册中心 | 配置 |
|------|------|---------|------|
| gateway | 8080 | Nacos（`nacos-discovery`） | 路由等全部在本地 `application.yaml` |
| user-service | 8085 | Nacos（`nacos-discovery`） | 全部在本地 `application.yaml` |
| order-service | 8086 | Nacos（`nacos-discovery`） | 全部在本地 `application.yaml` |

---

## 五、环境准备与启动

### 1. 前置环境

- JDK 21
- Maven 3.6+
- Nacos Server 3.2.3（控制台端口 18080，gRPC 8848）
- Sentinel Dashboard 1.8.9（控制台端口 8090）

> 无需在 Nacos 配置中心预置任何 Data ID，服务配置全部本地化。

### 2. 启动顺序

1. 启动 **Nacos Server**（注册中心 + 配置中心）
2. 启动 **Sentinel Dashboard**
3. 启动微服务（顺序不限）：
   - `gateway`（8080）
   - `user-service`（8085）
   - `order-service`（8086）

> 服务启动后会自动向 Sentinel 控制台（8090）上报心跳，此时才能在控制台为各资源配置限流规则。

### 3. 验证

| 请求 | 预期结果 |
|------|---------|
| `GET http://localhost:8080/user/local` | 经网关转发到用户服务本地查询 |
| `GET http://localhost:8080/user/callOrder` | 网关 → 用户服务 → 远程调用订单服务，返回订单信息 |
| `GET http://localhost:8080/order/get` | 经网关转发到订单服务 |
| 在 Sentinel 控制台给 `userLocalApi` 配 QPS=1 限流 | 高并发访问返回 429 限流提示 |

---

## 六、学习要点

- **混合技术栈**：网关保持响应式 WebFlux，业务模块使用主流 Spring MVC + OpenFeign，贴近生产实践；独立进程间技术栈互不影响。
- **声明式远程调用**：OpenFeign + `lb://` 服务名，配合 Spring Cloud LoadBalancer 做客户端负载均衡，SentinelFeign 提供降级兜底。
- **BOM 统一版本管理**：父 POM 仅导入两个 BOM，子模块不写版本。
- **Sentinel 注解 + 全局兜底双保险**：接口级 `@SentinelResource` 与 URL 级（webmvc v6x 适配）分层降级。
- **Sentinel 双适配**：网关用 webflux 适配（`BlockRequestHandler`），业务模块用 webmvc v6x 适配（`BlockExceptionHandler`），同一进程只启用一套。
- **网关新配置前缀**：`spring.cloud.gateway.server.webflux.routes`。
