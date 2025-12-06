## 秒杀模块压测方案（Load Test）

> 目标：在本地或测试环境中对秒杀接口进行高并发压测，验证 Redis + Lua + MQ + MySQL 整体链路的性能和稳定性。

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

### 2. Redis Lua 层压测（无 HTTP，仅脚本）

> 用来验证单机 Redis + Lua 的极限 QPS，排除网络与应用层开销。

- 使用 `redis-benchmark`：
  - 将秒杀 Lua 脚本写入文件 `lua_seckill.lua`；
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


