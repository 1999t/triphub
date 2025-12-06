## 行程创建与基础查询测试

> 覆盖行程的创建、详情查询（含缓存）、当前用户行程分页列表接口。

### 1. 创建行程 `POST /user/trip`

- **请求**
  - URL：`{{baseUrl}}/user/trip`
  - Header：
    - `Content-Type: application/json`
    - `token: {{userToken}}`
  - Body(JSON 示例)：
    ```json
    {
      "title": "成都三日美食行",
      "destinationCity": "成都",
      "startDate": "2025-12-20",
      "endDate": "2025-12-22",
      "days": 3,
      "visibility": 2
    }
    ```

- **预期结果**
  - HTTP 状态码：`200`
  - Body：
    ```json
    { "code": 0, "msg": "ok", "data": <新建行程ID> }
    ```
  - 记录返回的 `data` 作为后续 `/user/trip/{id}` 测试用的 `tripId`。

### 2. 行程详情（带缓存）`GET /user/trip/{id}`

- **请求**
  - URL：`{{baseUrl}}/user/trip/{{tripId}}`
  - Header：
    - `token: {{userToken}}`

- **预期结果**
  - 第一次请求：命中数据库，Redis 中写入 `cache:trip:{{tripId}}`，Body：
    ```json
    {
      "code": 0,
      "msg": "ok",
      "data": {
        "id": {{tripId}},
        "title": "成都三日美食行",
        "destinationCity": "成都"
      }
    }
    ```
  - 多次重复请求：命中缓存，返回同样的数据，接口响应时间明显缩短（可通过 Postman 中的响应时间或配合 Redis CLI 观察）。

### 3. 我的行程分页列表 `GET /user/trip/list`

- **请求**
  - URL：`{{baseUrl}}/user/trip/list?page=1&size=10`
  - Header：
    - `token: {{userToken}}`

- **预期结果**
  - Body：
    ```json
    {
      "code": 0,
      "msg": "ok",
      "data": {
        "total": 1,
        "records": [
          { "id": {{tripId}}, "title": "成都三日美食行" }
        ]
      }
    }
    ```


