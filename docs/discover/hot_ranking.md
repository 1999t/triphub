## 热门行程与热门目的地榜单测试

> 覆盖 `DiscoverController` 下热门行程、热门目的地接口的测试与 Redis ZSet 行为验证。

### 1. 热门行程列表 `GET /user/discover/hot-trips`

#### 1.1 准备数据

- 通过多次访问行程详情接口 `/user/trip/{{tripId}}`，触发行程浏览量累加，并写入 Redis ZSet：`hot:trip`。
- Redis CLI 验证：
  ```shell
  ZREVRANGE hot:trip 0 10 WITHSCORES
  ```

#### 1.2 请求与预期

- **请求**
  - URL：`{{baseUrl}}/user/discover/hot-trips?limit=10`
  - Header：
    - `token: {{userToken}}`

- **预期结果**
  - HTTP 状态码：`200`
  - Body：
    ```json
    {
      "code": 0,
      "msg": "ok",
      "data": [
        {
          "id": {{tripId}},
          "title": "成都三日美食行",
          "visibility": 2,
          "viewCount": 10,
          "likeCount": 0
        }
      ]
    }
    ```
  - 访问次数越多的行程排在越前，仅返回公开行程（`visibility = 2`）。

### 2. 热门目的地列表 `GET /user/discover/hot-destinations`

#### 2.1 准备数据

- 多个行程设置相同的 `destinationCity`，例如「成都」。
- 访问不同行程详情 `/user/trip/{{tripId}}`，会同时累积：
  - 行程层面的 ZSet：`hot:trip`（member=tripId）
  - 目的地层面的 ZSet：`hot:dest`（member=destinationCity）
- Redis CLI 验证：
  ```shell
  ZREVRANGE hot:dest 0 10 WITHSCORES
  ```

#### 2.2 请求与预期

- **请求**
  - URL：`{{baseUrl}}/user/discover/hot-destinations?limit=10`
  - Header：
    - `token: {{userToken}}`

- **预期结果**
  - HTTP 状态码：`200`
  - Body：
    ```json
    {
      "code": 0,
      "msg": "ok",
      "data": [
        "成都",
        "北京",
        "上海"
      ]
    }
    ```
  - 返回按热度排序的目的地城市名称列表，前面的是被访问次数更多的目的地。


