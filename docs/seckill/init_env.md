## 秒杀环境初始化与一键重置

> 目标：提供一份可以直接复制执行的环境初始化脚本和手动步骤，让开发 / QA 可以快速重置本地秒杀环境。

### 1. 数据库与 Redis 手动初始化（基础版）

#### 1.1 在 MySQL 中插入秒杀活动

```sql
INSERT INTO seckill_activity (id, title, place_id, stock, begin_time, end_time, status)
VALUES (1, '成都酒店秒杀', 1, 50, NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY), 1)
ON DUPLICATE KEY UPDATE
  title = VALUES(title),
  place_id = VALUES(place_id),
  stock = VALUES(stock),
  begin_time = VALUES(begin_time),
  end_time = VALUES(end_time),
  status = VALUES(status);
```

> 说明：使用固定 `id = 1`，并通过 `ON DUPLICATE KEY UPDATE` 支持重复执行（多次初始化不会报错，只会覆盖为最新配置）。

#### 1.2 在 Redis 中初始化库存

```shell
redis-cli set seckill:stock:1 50
```

> 说明：当 `SeckillActivityServiceImpl` 的自动加载逻辑存在时，也会在应用启动时从 DB 将 `seckill_activity.stock` 同步到 `seckill:stock:{id}`，本步骤主要用于显式重置。

### 2. 一键初始化脚本 `script/init_seckill.sh`

> 推荐在仓库根目录下执行：`bash script/init_seckill.sh`。

脚本位置：`script/init_seckill.sh`

核心行为：

- 向 MySQL 写入（或更新）一条 `seckill_activity` 记录，默认 `id = 1`；
- 在 Redis 中写入对应库存 Key：`seckill:stock:1`；
- 可多次执行，用于「重置」活动和库存。

支持的环境变量（可选）：

- `MYSQL_HOST`（默认：`localhost`）
- `MYSQL_PORT`（默认：`3306`）
- `MYSQL_USER`（默认：`root`）
- `MYSQL_PASSWORD`（默认：`123456`）
- `MYSQL_DB`（默认：`triphub`）
- `REDIS_CLI`（默认：`redis-cli`）
- `ACTIVITY_ID`（默认：`1`）
- `ACTIVITY_TITLE`（默认：`成都酒店秒杀`）
- `PLACE_ID`（默认：`1`）
- `STOCK`（默认：`50`）

示例：

```bash
cd triphub
bash script/init_seckill.sh

# 或自定义库存和活动 ID
STOCK=100 ACTIVITY_ID=2 bash script/init_seckill.sh
```

### 3. 单用户秒杀验证最小步骤

1. 启动 MySQL、Redis、RabbitMQ。
2. 执行初始化脚本：
   ```bash
   cd triphub
   bash script/init_seckill.sh
   ```
3. 使用 Postman 完成用户登录，拿到 `{{userToken}}`。
4. 调用 `POST /user/seckill/1`：
   - 首次请求预期：`code = 0`，`data` 为非空长整型订单 ID。
5. 使用 Redis CLI 检查：
   ```shell
   GET seckill:stock:1
   SMEMBERS seckill:order:1
   ```
6. 在 MySQL 中检查订单表与活动库存是否相应变化。


