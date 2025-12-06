## 秒杀异步下单与 MQ 消费流程

> 描述从 HTTP 秒杀请求到 RabbitMQ 消费落单的完整链路，方便开发与排查问题。

### 1. 总体流程概览

1. 用户调用 `POST /user/seckill/{activityId}`。
2. 服务端首先执行 Redis + Lua 预检：
   - 检查库存是否充足；
   - 检查是否一人一单；
   - 通过后原子扣减库存并记录用户。
3. 通过预检后，使用 `RedisIdWorker` 生成全局唯一 `orderId`。
4. 将订单信息封装为消息，投递到 RabbitMQ：
   - 交换机：`TRIPHUB_EXCHANGE`
   - 路由键：`seckill`
   - 队列：`TRIPHUB_SECKILL_QUEUE`
5. 消费端 `SeckillOrderListener` 从队列中消费消息：
   - 使用 Redisson 分布式锁保证同一用户并发消息只被处理一次；
   - 再次检查数据库，确保一人一单；
   - 插入订单记录，更新 `seckill_activity.stock`。

### 2. RabbitMQ 配置约定

- **交换机与队列**
  - 普通交换机：`TRIPHUB_EXCHANGE`
  - 死信交换机：`TRIPHUB_DL_EXCHANGE`
  - 普通队列：`TRIPHUB_SECKILL_QUEUE`
  - 死信队列：`TRIPHUB_SECKILL_DLQ`

- **典型配置项**
  - `x-dead-letter-exchange`：设置死信交换机；
  - `x-dead-letter-routing-key`：设置死信路由键；
  - 可选 TTL（如业务上需要订单超时处理）。

> 实际配置可在 `MqConfig` / `QueueConfig` 中查看，遵循与 dianping 秒杀类似的结构。

### 3. 消费端幂等与一人一单

- **幂等策略**
  1. 使用 Redisson 分布式锁：
     - 锁 Key：`lock:seckill:order:{userId}`
     - 同一用户并发消费时，只有拿到锁的消费线程可以继续。
  2. 数据库层面再次检查：
     - 查询 `order` 表中是否已存在相同 `userId + activityId` 的订单；
     - 如已存在，直接返回，不再重复插入。

- **典型伪代码**

```java
String lockKey = "lock:seckill:order:" + userId;
RLock lock = redissonClient.getLock(lockKey);
lock.lock();
try {
    // 1. 再查一次订单是否已存在
    // 2. 不存在则插入订单记录并扣减 seckill_activity.stock
} finally {
    lock.unlock();
}
```

### 4. 验证消费链路是否正常

1. 启动 RabbitMQ，并确认应用成功连接（日志中无持续 `AmqpConnectException`）。
2. 通过 `script/init_seckill.sh` 初始化活动与库存。
3. 使用 Postman 调用 `POST /user/seckill/1`，预期返回：
   ```json
   {
     "code": 0,
     "msg": "ok",
     "data": <orderId>
   }
   ```
4. 立即在 Redis 中检查：
   ```shell
   GET seckill:stock:1
   SMEMBERS seckill:order:1
   ```
5. 稍等数秒后，在 MySQL 中验证：
   ```sql
   SELECT * FROM `order` WHERE seckill_activity_id = 1;
   SELECT stock FROM seckill_activity WHERE id = 1;
   ```
   - 预期：
     - `order` 表新增一条记录，`id` 等于返回的 `orderId`；
     - `seckill_activity.stock` 从初始值减 1。

### 5. 常见问题排查指引

- **现象：接口返回成功，但 DB 中迟迟没有订单**
  - 优先检查 RabbitMQ 是否启动、连接配置是否正确；
  - 查看监听容器日志中是否有异常（权限、序列化、SQL 错误等）；
  - 确认可消费队列 `TRIPHUB_SECKILL_QUEUE` 中的消息是否被正常 ACK。

- **现象：Redis 库存已扣减，但 DB 订单插入失败**
  - 需要结合业务日志分析失败原因；
  - 在高并发场景下建议增加业务监控与告警，对比 Redis 与 MySQL 中库存数据，定期校对。


