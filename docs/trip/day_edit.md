## 行程按天编辑与条目管理测试

> 依赖前置步骤：已完成登录、获取 `userToken`，并通过「创建行程」接口拿到 `tripId`。

### 1. 按天查看行程详情 `GET /user/trip/day/detail`

- **请求**
  - URL：`{{baseUrl}}/user/trip/day/detail?tripId={{tripId}}&dayIndex=1`
  - Header：
    - `token: {{userToken}}`

- **预期结果**
  - HTTP 状态码：`200`
  - 第一次访问某天且未创建任何条目时：
    ```json
    {
      "code": 0,
      "msg": "ok",
      "data": {
        "id": null,
        "tripId": {{tripId}},
        "dayIndex": 1,
        "note": null,
        "items": []
      }
    }
    ```

### 2. 设置行程日备注 `PUT /user/trip/day/note`

- **请求**
  - URL：`{{baseUrl}}/user/trip/day/note`
  - Header：
    - `Content-Type: application/json`
    - `token: {{userToken}}`
  - Body(JSON)：
    ```json
    {
      "tripId": {{tripId}},
      "dayIndex": 1,
      "note": "第一天主要逛春熙路、宽窄巷子"
    }
    ```

- **预期结果**
  - HTTP 状态码：`200`
  - Body：`{ "code": 0, "msg": "ok", "data": null }`
  - 再次调用「按天查看行程详情」，`data.note` 字段更新为上述文案。

### 3. 新增行程条目 `POST /user/trip/item`

- **请求**
  - URL：`{{baseUrl}}/user/trip/item`
  - Header：
    - `Content-Type: application/json`
    - `token: {{userToken}}`
  - Body(JSON 示例)：
    ```json
    {
      "tripId": {{tripId}},
      "dayIndex": 1,
      "type": "FOOD",
      "placeId": 1,
      "startTime": "10:00:00",
      "endTime": "12:00:00",
      "memo": "春熙路小吃打卡"
    }
    ```

- **预期结果**
  - HTTP 状态码：`200`
  - Body：
    ```json
    { "code": 0, "msg": "ok", "data": <新建条目ID> }
    ```
  - 记录返回的 `data` 作为后续更新 / 删除条目的 `tripItemId`。

### 4. 更新行程条目 `PUT /user/trip/item`

- **请求**
  - URL：`{{baseUrl}}/user/trip/item`
  - Header：
    - `Content-Type: application/json`
    - `token: {{userToken}}`
  - Body(JSON 示例)：
    ```json
    {
      "id": {{tripItemId}},
      "type": "FOOD",
      "placeId": 1,
      "startTime": "10:30:00",
      "endTime": "12:30:00",
      "memo": "调整为晚些出发"
    }
    ```

- **预期结果**
  - HTTP 状态码：`200`
  - Body：`{ "code": 0, "msg": "ok", "data": null }`
  - 再次调用「按天查看行程详情」，对应条目的时间和备注更新。

### 5. 删除行程条目 `DELETE /user/trip/item/{id}`

- **请求**
  - URL：`{{baseUrl}}/user/trip/item/{{tripItemId}}`
  - Header：
    - `token: {{userToken}}`

- **预期结果**
  - HTTP 状态码：`200`
  - Body：`{ "code": 0, "msg": "ok", "data": null }`
  - 再次调用「按天查看行程详情」，`items` 数组长度减少，对应条目被删除。


