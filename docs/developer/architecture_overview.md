## TripHub 架构与核心流程概览

> 面向开发者与新同学，快速理解 TripHub 的整体架构与关键业务流程（JWT 登录、行程缓存、热门榜单、用户画像与 AI 推荐）。

### 1. 整体架构

- **后端框架**
  - Spring Boot 2.7.x
  - MyBatis-Plus 作为 ORM
  - Redis 作为缓存与热点数据存储
  - Redisson 提供分布式锁能力

- **模块划分**
  - `triphub-common`：通用组件（`Result`、`JwtUtil`、`BaseContext` 等）
  - `triphub-pojo`：实体、DTO、VO
  - `triphub-server`：业务服务（Controller / Service / Mapper / Utils）

- **核心中间件角色**
  - **MySQL**：持久化业务数据（用户、行程、收藏、用户画像等）
  - **Redis**：
    - 行程缓存（`cache:trip:{id}`）
    - 热门榜单（`hot:trip`、`hot:dest`）

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

### 5. 用户画像与 AI 行程推荐（概要）

1. 用户通过 `/user/profile` 保存画像 JSON（兴趣标签、预算、出行天数、风格等）。
2. 行程收藏行为通过 `trip_favorite` 表进行统计，反哺到画像中，例如偏好城市。
3. AI 行程规划 `POST /user/ai/trip-plan`：
   - 读取当前用户画像与请求中的目的地、天数等参数；
   - 构造 Trip 草稿，并调用外部 LLM 生成「为什么这条行程适合你」的解释文案；
   - 将生成结果写入 Trip 表并返回给前端。
4. 推荐接口可以结合 Redis 热门榜单、收藏统计与用户画像做规则打分，再将候选行程 + 画像信息作为上下文喂给 LLM，生成带理由的推荐列表。

### 6. 对新人快速上手的建议

- 先按顺序阅读：
  1. `TripHub代码开发规划.md`（整体开发规则）
  2. 本文（架构与流程）
  3. `docs/auth/login.md` 与 `docs/trip/*.md`（最常用接口）
- 搭环境时优先保证：
  - MySQL / Redis 都已启动；
  - `db/init_triphub.sql` 已执行。


