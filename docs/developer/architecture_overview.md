## TripHub 架构与核心流程概览

> 面向开发者与新同学，快速理解 TripHub 的整体架构与关键业务流程（JWT 登录、行程缓存、热门榜单、秒杀预检 + MQ）。

### 1. 整体架构

- **后端框架**
  - Spring Boot 2.7.x
  - MyBatis-Plus 作为 ORM
  - Redis 作为缓存与热点数据存储
  - RabbitMQ 用于异步下单
  - Redisson 提供分布式锁能力

- **模块划分**
  - `triphub-common`：通用组件（`Result`、`JwtUtil`、`BaseContext` 等）
  - `triphub-pojo`：实体、DTO、VO
  - `triphub-server`：业务服务（Controller / Service / Mapper / Utils）

- **核心中间件角色**
  - **MySQL**：持久化业务数据（用户、行程、秒杀活动、订单等）
  - **Redis**：
    - 行程缓存（`cache:trip:{id}`）
    - 热门榜单（`hot:trip`、`hot:dest`）
    - 秒杀库存与一人一单状态（`seckill:stock:{id}`、`seckill:order:{id}`）
  - **RabbitMQ**：
    - 秒杀下单异步写库，削峰填谷

### 2. JWT 登录流程

1. 客户端调用 `POST /user/auth/sendCode` 获取验证码（当前为固定值 `"1234"`）。
2. 客户端调用 `POST /user/auth/login`：
   - 校验手机号 + 验证码；
   - 若用户不存在则创建；
   - 使用 `JwtUtil.createJWT` 生成用户端 JWT；
   - 将用户关键信息写入 Redis Hash：`login:user:{token}`。
3. 客户端后续请求在 Header 中携带 `token`：
   - `JwtTokenUserInterceptor` 拦截 `/user/**`；
   - 从 Header 读取 token 并解析；
   - 解析成功后，将用户 ID 写入 `BaseContext`；
   - 控制器和 Service 层通过 `BaseContext.getCurrentId()` 获取当前用户。

### 3. Trip 缓存逻辑流程

> 以 `GET /user/trip/{id}` 为例。

1. 拼接缓存 Key：`cache:trip:{id}`。
2. 从 Redis 读取缓存 JSON：
   - 未命中：
     - 回源 DB 查询 Trip；
     - 若存在：写入逻辑过期缓存（带过期时间戳）；
     - 若不存在：可写入短 TTL 的空值防止穿透。
   - 命中：
     - 若逻辑未过期：直接返回；
     - 若逻辑已过期：
       - 返回旧数据；
       - 尝试获取互斥锁，成功后异步重建缓存。
3. 行程详情被访问时，同时更新：
   - Trip 实体的 `viewCount`（按策略批量或异步写回 DB）；
   - 热门榜单 ZSet：`hot:trip` 与 `hot:dest`。

### 4. 热门榜单分数累计流程

- **热门行程榜（`hot:trip`）**
  - ZSet Key：`hot:trip`
  - member：`tripId`
  - score：累积浏览次数

- **热门目的地榜（`hot:dest`）**
  - ZSet Key：`hot:dest`
  - member：目的地城市名（如 `"成都"`）
  - score：该城市下所有公开行程的累积浏览次数

- **更新时机**
  - 每次成功访问行程详情（且行程对当前访问者可见）：
    - `ZINCRBY hot:trip 1 {tripId}`
    - `ZINCRBY hot:dest 1 {destinationCity}`

- **查询接口**
  - `GET /user/discover/hot-trips`：从 `hot:trip` 取 Top N，再批量查 Trip 详情。
  - `GET /user/discover/hot-destinations`：从 `hot:dest` 取 Top N，直接返回城市名列表。

### 5. 秒杀预检 + MQ 异步下单流程

> 核心目标：在高并发场景下，保证库存准确、一人一单、接口快速返回。

1. **请求入口**
   - `POST /user/seckill/{activityId}`。
   - 从 `BaseContext` 获取当前用户 ID。

2. **Redis + Lua 预检**
   - Key：
     - `seckill:stock:{activityId}`：库存
     - `seckill:order:{activityId}`：已下单用户集合
   - 步骤：
     1. 读取库存，若 `<= 0` → 返回错误「Seckill stock is not enough」；
     2. 检查用户是否已在 Set 中，若存在 → 返回错误「User has already placed an order for this activity」；
     3. 使用 `DECR` 扣减库存，并 `SADD` 写入用户 ID；
     4. 返回 `0` 表示通过。

3. **消息入队**
   - 通过 `RedisIdWorker.nextId("order")` 生成全局唯一订单 ID；
   - 封装消息（`orderId`, `userId`, `activityId` 等）发送到：
     - 交换机：`TRIPHUB_EXCHANGE`
     - 路由键：`seckill`
     - 队列：`TRIPHUB_SECKILL_QUEUE`

4. **MQ 消费与落单**
   - 监听器：`SeckillOrderListener`；
   - 使用 Redisson 分布式锁：`lock:seckill:order:{userId}` 保证用户维度的串行消费；
   - 再次查询 DB 确认一人一单；
   - 插入 `order` 表记录；
   - 基于乐观方式扣减 `seckill_activity.stock`。

5. **失败与重试**
   - 消费失败时，根据 MQ 配置进入重试或死信队列；
   - 需要结合业务日志排查根因，避免出现「Redis 已扣减但 DB 长期未落单」的情况。

### 6. 对新人快速上手的建议

- 先按顺序阅读：
  1. `TripHub代码开发规划.md`（整体开发规则）
  2. 本文（架构与流程）
  3. `docs/auth/login.md` 与 `docs/trip/*.md`（最常用接口）
  4. `docs/seckill/*.md`（高并发特性模块）
- 搭环境时优先保证：
  - MySQL / Redis / RabbitMQ 都已启动；
  - `db/init_triphub.sql` 已执行；
  - `script/init_seckill.sh` 能够顺利跑通。


