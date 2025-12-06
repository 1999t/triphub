## 秒杀 Lua 预检脚本逻辑与验证

> 说明 Redis + Lua 层面的库存预检与一人一单校验逻辑，并给出可在 Redis CLI 中直接执行的验证步骤。

### 1. Key 设计与行为约定

- **库存 Key**
  - 格式：`seckill:stock:{activityId}`
  - 类型：String
  - 语义：当前剩余可售库存（整型）
- **下单用户集合 Key**
  - 格式：`seckill:order:{activityId}`
  - 类型：Set
  - 语义：已成功抢购的用户 ID 集合

### 2. Lua 脚本核心逻辑（伪代码）

> 实际脚本内嵌在服务端（`SeckillActivityServiceImpl` 相关逻辑），这里使用等价伪代码便于理解与验证。

```lua
-- KEYS[1] = seckill:stock:{activityId}
-- KEYS[2] = seckill:order:{activityId}
-- ARGV[1] = userId

local stock = tonumber(redis.call('GET', KEYS[1]))
if not stock or stock <= 0 then
  return 1  -- 库存不足
end

if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
  return 2  -- 一人一单：用户已下单
end

redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return 0    -- 预检通过
```

### 3. 在 Redis CLI 中手动验证

#### 3.1 准备数据

```shell
redis-cli set seckill:stock:1 2
redis-cli del seckill:order:1
```

#### 3.2 定义 Lua 脚本（临时 EVAL）

```shell
redis-cli --eval lua_seckill.lua seckill:stock:1 seckill:order:1 , 1001
```

若不想写文件，可以直接在命令行内联：

```shell
redis-cli EVAL "
local stock = tonumber(redis.call('GET', KEYS[1]))
if not stock or stock <= 0 then
  return 1
end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
  return 2
end
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return 0
" 2 seckill:stock:1 seckill:order:1 1001
```

#### 3.3 预期返回值说明

- `0`：预检通过
  - `seckill:stock:1` 减 1；
  - `seckill:order:1` 中新增用户 `1001`。
- `1`：库存不足
  - 不再扣减库存；
  - 不再新增用户。
- `2`：一人一单限制命中
  - 库存不变；
  - 用户集合不变。

可以多次执行来模拟不同场景：

```shell
# 第一次，成功
redis-cli EVAL "<同上脚本>" 2 seckill:stock:1 seckill:order:1 1001

# 第二次，同一用户再次下单，预期返回 2
redis-cli EVAL "<同上脚本>" 2 seckill:stock:1 seckill:order:1 1001

# 使用另一个用户直到库存耗尽
redis-cli EVAL "<同上脚本>" 2 seckill:stock:1 seckill:order:1 1002
redis-cli EVAL "<同上脚本>" 2 seckill:stock:1 seckill:order:1 1003  # 预期返回 1
```

### 4. 接口返回与 Lua 结果的映射

- `return 0` → 接口返回：
  ```json
  {
    "code": 0,
    "msg": "ok",
    "data": <全局订单ID>
  }
  ```
- `return 1` → 接口返回：
  ```json
  { "code": 1, "msg": "Seckill stock is not enough", "data": null }
  ```
- `return 2` → 接口返回：
  ```json
  { "code": 1, "msg": "User has already placed an order for this activity", "data": null }
  ```


