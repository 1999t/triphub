## 秒杀模块压测方案（Load Test）

> 目标：在本地或测试环境中对秒杀接口进行高并发压测，验证 Redis + Lua + MQ + MySQL 整体链路的性能和稳定性，形成一套可重复执行的测试脚本。

---

### 0. 10 分钟从 0 到 1 部署 + 压测脚本（推荐先走一遍）

1. **本机打包后端 JAR**
   - 在项目根目录执行：
   ```bash
   cd triphub
   mvn -pl triphub-server -am clean package
   ```
   - 期望在 `triphub-server/target/` 下生成 `triphub-server-1.0-SNAPSHOT.jar`。

2. **启动一整套容器环境**
   - 在 `triphub` 根目录执行：
   ```bash
   docker-compose up --build -d
   ```
   - 等待 MySQL / Redis / RabbitMQ / triphub-server 全部就绪（`docker ps` 确认容器均为 `healthy` 或 `Up`）。

3. **初始化数据库与秒杀活动**
   - Docker 会自动执行 `db/init_triphub.sql` 建库建表；
   - 在宿主机（本机）执行脚本初始化活动和 Redis 库存（脚本会通过 TCP 连接 `127.0.0.1:3306` 与 `127.0.0.1:6379`）：
   ```bash
   cd triphub
   bash script/init_seckill.sh
   ```
   - 用 MySQL/Redis 简单确认：
   ```sql
   SELECT id, title, stock, status FROM seckill_activity;
   ```
   ```shell
   GET seckill:stock:1
   ```

3. **准备一个登录用户 token**
   - 使用 Postman / curl 调用验证码登录链路（示例）：
   ```bash
   # 1) 请求验证码（实际项目中验证码固定写死为 1234 或通过日志打印）
   curl -X POST "http://localhost:8090/user/auth/sendCode?phone=13900000001"
   # 2) 带验证码登录
   curl -X POST "http://localhost:8090/user/auth/login" \
     -H "Content-Type: application/json" \
     -d '{"phone":"13900000001","code":"1234"}'
   ```
   - 从响应中取出 `data.token`，在后续压测工具里作为 `token` 请求头。

4. **用 JMeter 跑一次 `POST /user/seckill/1` 压测**
   - Thread Group：
     - 线程数：800～1000（略大于库存）
     - Ramp-Up：5 秒
     - 循环次数：1
   - HTTP Request：
     - Method：`POST`
     - URL：`http://localhost:8090/user/seckill/1`
     - Header：`token: {{userToken}}`
   - 观察聚合报告中的 QPS、RT、错误分布。

5. **压测结束后对账**
   - Redis：
   ```shell
   GET seckill:stock:1
   SCARD seckill:order:1
   ```
   - MySQL：
   ```sql
   SELECT COUNT(*) FROM `order` WHERE seckill_activity_id = 1;
   SELECT stock FROM seckill_activity WHERE id = 1;
   ```
   - 期望：成功订单数 ≤ 初始库存；同一 `user_id + seckill_activity_id` 无重复记录。

> 上面 0~5 步走通一次之后，可以再按下面更细的分层压测方案做拆解和调优。

---

### 1. 压测前准备

- **环境依赖**
  - MySQL：已导入 `db/init_triphub.sql`；
  - Redis：已启动，支持 Lua；
  - RabbitMQ：已启动，应用成功连通；
  - TripHub 应用：`triphub-server` 已以生产配置方式启动。

- **秒杀活动与库存**
  - 使用脚本初始化：
    ```bash
    cd triphub
    bash script/init_seckill.sh
    ```
  - 或手动保证：
    - `seckill_activity` 中存在 `id = 1` 且 `stock` 为预期值；
    - Redis 中：
      ```shell
      GET seckill:stock:1
      ```

- **测试账号**
  - 准备若干手机号，通过登录接口提前登录，拿到对应 `userToken`；
  - 简化起见，压测时也可以先用同一个 token 验证「一人一单」逻辑。

---

### 2. Redis Lua 层压测（无 HTTP，仅脚本）

> 用来验证单机 Redis + Lua 的极限 QPS，排除网络与应用层开销。

- 使用 `redis-benchmark`：
  - 将秒杀 Lua 脚本写入文件 `lua_seckill.lua`（内容与 `SeckillServiceImpl` 中的脚本保持一致）；
  - 使用 `--eval` 执行：
    ```bash
    redis-benchmark -n 100000 -c 200 \
      eval "$(cat lua_seckill.lua)" 2 seckill:stock:1 seckill:order:1 1001
    ```
  - 观察：
    - 平均 RT；
    - QPS；
    - 是否出现错误（例如返回值异常、连接中断）。

### 3. HTTP 压测（JMeter 示例）

#### 3.1 基本配置

- 创建 Thread Group：
  - 线程数：推荐略大于库存（例如库存 500，则线程数 800～1000）；
  - Ramp-Up 时间：1～5 秒；
  - 循环次数：1（单次秒杀压测）。

- 添加 HTTP Request：
  - Method：`POST`
  - URL：`http://localhost:8090/user/seckill/1`
  - Header：
    - `token: {{userToken}}`

- 添加聚合报告 / Summary Report 监听器，观察：
  - 吞吐量（Throughput）；
  - 平均响应时间、90/95/99 分位；
  - 错误率。

#### 3.2 预期结果

- 成功响应数不超过 Redis 初始库存（例如 500）；
- 同一用户最多只成功一次，其余请求返回「User has already placed an order for this activity」；
- 失败请求主要为：
  - `Seckill stock is not enough`；
  - 或一人一单限制消息。

### 4. MQ 堆积量与消费速率评估

> 目标：在高并发秒杀下单时，验证 MQ 队列的堆积情况与消费处理能力。

- 压测期间重点观察：
  - `TRIPHUB_SECKILL_QUEUE` 中 Ready 消息数量的变化；
  - 消费者数与消费速率（messages/s）。

- 粗略估算：
  - 假设秒杀接口瞬时达到 1200 TPS；
  - Redis + Lua 预检通过率为 50%；
  - 则 MQ 入队速率约为 600 msg/s；
  - 若单消费者处理能力为 200 msg/s，则需要至少 3 个并发消费者线程以避免长时间堆积。

### 5. 验证数据一致性

压测结束后，进行如下检查：

1. Redis：
   ```shell
   GET seckill:stock:1
   SCARD seckill:order:1
   ```
2. MySQL：
   ```sql
   SELECT COUNT(*) FROM `order` WHERE seckill_activity_id = 1;
   SELECT stock FROM seckill_activity WHERE id = 1;
   ```
3. 预期：
   - `SCARD seckill:order:1` 与成功订单数接近（每个用户仅一条记录）；
   - `seckill_activity.stock` ≈ 初始库存 - 成功订单数；
   - 无同一 `user_id + seckill_activity_id` 的重复订单。

### 6. 建议的后续优化方向

- 将压测脚本（JMeter `.jmx` 或 Locust 脚本）也纳入仓库管理；
- 增加简单的监控脚本，定时对比 Redis 与 MySQL 中的库存与订单数量；
- 在压测报告中记录不同并发量下的 RT/QPS/错误率曲线，为后续优化提供基线数据。


