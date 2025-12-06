## 用户认证与登录接口测试

> 覆盖验证码发送、登录、当前用户信息三类接口，对应 `AuthController` 与用户上下文。

### 1. 发送验证码 `POST /user/auth/sendCode`

- **请求**
  - URL：`{{baseUrl}}/user/auth/sendCode`
  - Header：`Content-Type: application/json`
  - Body(JSON)：
    ```json
    { "phone": "{{phone}}", "code": "" }
    ```

- **预期结果**
  - HTTP 状态码：`200`
  - Body：
    ```json
    { "code": 0, "msg": "ok", "data": "1234" }
    ```

### 2. 用户登录 `POST /user/auth/login`

- **请求**
  - URL：`{{baseUrl}}/user/auth/login`
  - Header：`Content-Type: application/json`
  - Body(JSON)：
    ```json
    { "phone": "{{phone}}", "code": "1234" }
    ```

- **预期结果**
  - HTTP 状态码：`200`
  - Body 中：
    - `code = 0`
    - `data` 为一串 JWT 字符串（非空）
  - Postman Tests 脚本自动把 `data` 写入集合变量 `userToken`。

### 3. 当前用户信息 `GET /user/profile/me`

- **请求**
  - URL：`{{baseUrl}}/user/profile/me`
  - Header：
    - `token: {{userToken}}`

- **预期结果**
  - 已登录（`userToken` 合法）：
    ```json
    {
      "code": 0,
      "msg": "ok",
      "data": {
        "id": 1,
        "phone": "{{phone}}"
      }
    }
    ```
  - 未携带或携带无效 token：
    - `code = 1`，`msg = "未登录或 token 无效"`。


