# GatewayWebflux Sentinel 微服务项目修复汇报文档

---

## 一、项目概述

### 1.1 项目结构

```Plaintext
GatewayWebflux/
├── pom.xml                                   # 聚合父工程
├── gateway/                                  # API网关模块（端口8080）
├── user-service/                             # 用户服务模块（端口8085）
└── order-service/                            # 订单服务模块（端口8086）
```

### 1.2 技术栈

| 组件 | 版本 | 用途 |
|---|---|---|
| Spring Boot | 3.5.16 | 应用框架 |
| Spring Cloud | 2025.0.3 | 微服务框架 |
| Spring Cloud Alibaba | 2025.0.0.0 | 服务治理 |
| Sentinel | 1.8.9 | 流量控制/熔断 |
| Nacos | 3.2.3 | 服务注册/配置中心 |
| JDK | 21 | 运行环境 |

---

## 二、问题描述与根因分析

### 2.1 问题现象

user-service 模块启动失败，报错信息：

```
Ambiguous mapping. Cannot map 'userController' method
com.qsx.user.controller.UserController#userLocalBlock(BlockException)
to {GET /user/callOrder}: There is already 'userController' bean method
com.qsx.user.controller.UserController#callOrder() mapped.
```

### 2.2 根因分析

**Spring WebFlux 请求映射机制冲突**：

1. 在 `@RestController` 标记的类中，Spring WebFlux 会扫描所有公共方法作为请求处理方法
2. 当 `@SentinelResource` 的 `blockHandler` 方法定义在 Controller 内部时
3. Spring 会尝试为该方法推断请求映射路径，导致与已有路径冲突

### 2.3 影响范围

| 模块 | 影响 |
|---|---|
| user-service | 启动失败 |
| order-service | 存在相同问题风险 |
| gateway | 正常 |

---

## 三、修复方案

### 3.1 解决方案

将 `blockHandler` 方法移到**独立的外部类**中，使用 `blockHandlerClass` 属性指定：

```java
@SentinelResource(value = "userLocalApi", 
                  blockHandlerClass = UserControllerBlockHandler.class, 
                  blockHandler = "userLocalBlock")
```

**关键要求**：外部类中的 `blockHandler` 方法必须是 **public static** 方法。

### 3.2 修改文件清单

| 文件路径 | 修改类型 | 说明 |
|---|---|---|
| `user-service/src/main/java/com/qsx/user/controller/UserController.java` | 修改 | 使用 `blockHandlerClass` 属性 |
| `user-service/src/main/java/com/qsx/user/controller/UserControllerBlockHandler.java` | 新建 | 存放 `userLocalBlock` 静态方法 |
| `order-service/src/main/java/com/qsx/order/controller/OrderController.java` | 修改 | 使用 `blockHandlerClass` 属性 |
| `order-service/src/main/java/com/qsx/order/controller/OrderControllerBlockHandler.java` | 新建 | 存放 `orderBlockHandler` 静态方法 |

### 3.3 其他修复

| 修复项 | 文件 | 说明 |
|---|---|---|
| Gateway 配置前缀更新 | `gateway/src/main/resources/application.yaml` | 使用新格式 `spring.cloud.gateway.server.webflux.routes` |
| Sentinel 配置类修复 | `user-service/config/SentinelWebFluxConfig.java` | 使用 `BlockRequestHandler` 接口（非 `WebFluxBlockExceptionHandler`） |
| Sentinel 配置类修复 | `order-service/config/SentinelWebFluxConfig.java` | 使用 `BlockRequestHandler` 接口 |
| blockHandler 参数类型 | 所有 Controller | 使用 `BlockException` 而非 `Throwable` |

---

## 四、验证结果

### 4.1 编译验证

```
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Reactor Summary for GatewayWebflux 1.0-SNAPSHOT:
[INFO] GatewayWebflux ..................................... SUCCESS
[INFO] gateway ............................................ SUCCESS
[INFO] user-service ....................................... SUCCESS
[INFO] order-service ...................................... SUCCESS
```

### 4.2 启动验证

| 服务 | 端口 | 启动状态 | 注册到Nacos |
|---|---|---|---|
| user-service | 8085 | ✅ 成功 | ✅ 已注册 |
| order-service | 8086 | ✅ 成功 | ✅ 已注册 |
| gateway | 8080 | ✅ 成功 | ✅ 已注册 |

### 4.3 当前服务状态

```
✅ 所有服务已停止
✅ 端口 8080、8085、8086 均已释放
```

---

## 五、正确启动步骤

### 5.1 前置条件

| 依赖服务 | 地址 | 说明 |
|---|---|---|
| Nacos Server | `127.0.0.1:8848` | 服务注册与配置中心 |
| Sentinel Dashboard | `localhost:8090` | 流量控制控制台 |

### 5.2 启动顺序

```
步骤1: order-service  (端口 8086)
步骤2: user-service   (端口 8085)  
步骤3: gateway        (端口 8080)
```

**原因**：user-service 需要调用 order-service，gateway 需要发现并路由到两个服务。

### 5.3 启动命令

在项目根目录 `GatewayWebflux` 下，分别在不同终端执行：

```bash
# 终端1 - 启动 order-service
cd order-service
mvn spring-boot:run

# 终端2 - 启动 user-service
cd user-service
mvn spring-boot:run

# 终端3 - 启动 gateway
cd gateway
mvn spring-boot:run
```

### 5.4 接口测试

```bash
# 通过网关访问 user-service 本地接口
curl http://localhost:8080/user/local

# 通过网关访问 user-service 远程调用接口
curl http://localhost:8080/user/callOrder

# 通过网关访问 order-service 接口
curl http://localhost:8080/order/get
```

---

## 六、Sentinel 学习要点

### 6.1 核心概念

| 概念 | 说明 |
|---|---|
| 资源 | 受保护的代码片段，通过 `@SentinelResource` 标记 |
| 流控规则 | 限制请求速率，保护系统不被冲垮 |
| 降级规则 | 当服务异常时自动降级，返回兜底数据 |
| blockHandler | 流控/熔断时的兜底方法 |
| fallback | 业务异常时的降级方法 |

### 6.2 资源配置

在 Sentinel Dashboard 中配置规则：

1. **簇点链路**：查看所有受保护资源
2. **流控规则**：为资源设置 QPS 阈值
3. **降级规则**：为资源设置异常比例/慢调用比例

---

## 七、文档更新

设计文档 [GatewayWebflux 微服务Sentinel学习完整项目设计方案（修复完善完整版）.md](file:///D:/javaSE_EE/Spring-Cloud-Alibaba/gateway-test/GatewayWebflux%20微服务Sentinel学习完整项目设计方案（修复完善完整版）.md) 已同步更新：

1. 添加了实际项目结构章节
2. 添加了关键修复内容说明
3. 修复了代码示例中的 `BlockException` 参数类型
4. 添加了响应式 `@SentinelResource` 的局限性说明

---

## 八、总结

| 项目 | 状态 |
|---|---|
| Maven 编译 | ✅ 通过 |
| 服务启动 | ✅ 成功 |
| Nacos 注册 | ✅ 正常 |
| 问题修复 | ✅ 完成 |
| 当前服务 | ✅ 已停止 |

项目已修复完成，可以按照文档学习 Sentinel 的限流和熔断功能。

---

**汇报时间**：2026-07-24
**项目状态**：✅ 已修复，可正常运行
