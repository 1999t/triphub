## 用户画像 & RAG 味道个性化推荐（后端 API 版）

> 目标：在不引入向量数据库的前提下，用最小改动实现「有 RAG 味道」的个性化推荐 Demo：  
> - 首次使用时通过 Setup 问卷收集用户画像；  
> - 在 AI 行程规划和热门推荐中使用用户画像 + 已有行程/热门数据做检索增强，再交给大模型生成自然语言推荐结果；  
> - 全程只提供后端 API，使用 Postman 即可演示。

---

### 1. 数据模型设计（支持画像字段频繁演进）

- **1.1 `user_profile` 表（建议）**

  - 用一张独立表保存用户画像，主要字段：
    - `id`：主键
    - `user_id`：用户 ID（唯一索引）
    - `profile_json`：`JSON` / `text` 字段，保存画像内容
    - `create_time` / `update_time`
  - `profile_json` 示例（可随时新增/删除字段）：

  ```json
  {
    "tags": [
      {"code": "food", "name": "美食", "weight": 5},
      {"code": "photo", "name": "摄影", "weight": 3}
    ],
    "budget": "1000-3000",
    "duration": "4-7",
    "travel_style": "slow",
    "companion": "friends"
  }
  ```

- **1.2 行为数据复用（无需新增表）**

  - 行程浏览：复用 Trip 详情中已有的 `viewCount` + Redis `hot:trip` / `hot:dest`。
  - 行程收藏、点赞（若后续实现）：可在画像更新时一起写入 `profile_json` 中的统计字段，例如：

  ```json
  {
    "stats": {
      "totalTripsViewed": 25,
      "totalTripsStarred": 5,
      "topCities": ["成都", "重庆"]
    }
  }
  ```

---

### 2. 用户 Setup & 画像 API 设计

> 只提供后端 REST API，通过 JWT 获取当前用户 ID，不做前端页面。

- **2.1 获取当前用户画像**

  - **接口**：`GET /user/profile`
  - **说明**：
    - 从 `BaseContext.getCurrentId()` 读取当前用户 ID；
    - 查询 `user_profile` 表，返回 `profile_json`，若不存在返回 `{}`。
  - **响应示例**：

  ```json
  {
    "code": 0,
    "msg": "ok",
    "data": {
      "tags": [
        {"code": "food", "name": "美食", "weight": 5},
        {"code": "photo", "name": "摄影", "weight": 3}
      ],
      "budget": "1000-3000",
      "duration": "4-7",
      "travel_style": "slow",
      "companion": "friends"
    }
  }
  ```

- **2.2 首次 Setup / 后续更新画像**

  - **接口**：`POST /user/profile`
  - **说明**：
    - Body 为自由扩展的 JSON，后端只做基础校验与白名单过滤，然后整体落到 `profile_json`。
    - 若不存在记录则 `INSERT`，否则 `UPDATE`。
  - **请求示例**：

  ```json
  {
    "tags": [
      {"code": "food", "name": "美食", "weight": 5},
      {"code": "photo", "name": "摄影", "weight": 3}
    ],
    "budget": "1000-3000",
    "duration": "4-7",
    "travel_style": "slow",
    "companion": "friends"
  }
  ```

  - **响应示例**：

  ```json
  {
    "code": 0,
    "msg": "ok",
    "data": null
  }
  ```

---

### 3. 「RAG 味道」推荐总体思路（无向量库版本）

> 思路：用现有 MySQL + Redis 先做「检索 + 过滤 + 简单打分」，再把**用户画像 + 候选行程/目的地数据**拼进 LLM Prompt，让大模型生成个性化推荐理由和排序结果，从而形成 RAG 味道。

- **3.1 候选数据检索**

  - AI 行程规划：
    - 根据目的地、天数等基础参数，在 Trip 表中查出该城市的历史行程模板（平台预置 + 用户公开行程）。
  - 热门推荐：
    - 从 `hot:trip` ZSet 中取 Top N 行程 ID；
    - 过滤出当前用户可见的行程（公开/自己创建）。

- **3.2 基于用户画像的规则打分**

  - 根据 `profile_json.tags` 与行程标签（后续可在 Trip 表或 JSON 中维护）做简单匹配打分：
    - 标签命中 +1，权重高（如 5 星）再额外加分；
    - 预算、时长不匹配的直接降权或过滤。

- **3.3 将检索结果与画像一起喂给 LLM**

  - 组装一个 Prompt，包含：
    - 用户画像摘要（兴趣、预算、时长、常用出行方式）；
    - 若干条候选行程/目的地的精简信息（标题、城市、亮点、基础标签、历史热度）。
  - 要求大模型：
    - 选出若干条最匹配的方案；
    - 给出简短推荐理由（明确说明「因为你喜欢美食/预算在 xxx」等）。

---

### 4. 推荐相关 API 设计

- **4.1 带用户画像的 AI 行程规划**

  - **接口**：`POST /user/ai/trip-plan`
  - **请求示例**：

  ```json
  {
    "destinationCity": "成都",
    "days": 3,
    "startDate": "2025-10-01"
  }
  ```

  - **服务侧流程（伪代码级）**：
    1. 从 `BaseContext` 获取 `userId`，读取 `user_profile.profile_json`；
    2. 从 Trip 表中按 `destinationCity` 查询一批历史行程模板；
    3. 将画像 + 历史行程摘要 + 用户输入拼接为 Prompt；
    4. 调用外部大模型 API（如 OpenAI）生成每日行程建议；
    5. 将生成结果落库为一条 Trip 记录，并返回给前端。

  - **响应示例（简化）**：

  ```json
  {
    "code": 0,
    "msg": "ok",
    "data": {
      "id": 123,
      "title": "成都 3 日美食+摄影行",
      "days": 3,
      "destinationCity": "成都",
      "planSummary": "根据你偏好的美食和摄影，安排了宽窄巷子、人民公园、春熙路夜市等...",
      "llmExplanation": "因为你将美食标记为 5 星、摄影标记为 3 星，本次行程优先安排了夜市与适合拍照的网红景点。"
    }
  }
  ```

- **4.2 基于画像的「为你推荐行程」列表**

  - **接口**：`GET /user/discover/recommend-trips?limit=10`
  - **实现思路**：
    1. 从 Redis `hot:trip` ZSet 中取 Top N 行程 ID；
    2. 过滤不可见行程；
    3. 根据用户画像做简单规则打分（标签/预算/时长等），得到一个初步排序；
    4. 选取前 K 条（如 20 条）作为候选，拼接画像 + 候选摘要交给 LLM 进行二次排序与理由生成；
    5. 最终返回给前端，每条数据附带推荐理由。

  - **响应示例（结构）**：

  ```json
  {
    "code": 0,
    "msg": "ok",
    "data": [
      {
        "tripId": 101,
        "title": "成都三日美食行",
        "destinationCity": "成都",
        "viewCount": 120,
        "reason": "你将美食标记为 5 星，并多次浏览成都相关行程，因此优先推荐该美食主题路线。"
      },
      {
        "tripId": 102,
        "title": "重庆三日夜景+火锅行",
        "destinationCity": "重庆",
        "viewCount": 90,
        "reason": "你最近收藏了多条西南城市行程，并偏好摄影和夜景。"
      }
    ]
  }
  ```

---

### 5. 面试 Demo 演示脚本建议

1. 使用手机号登录，拿到用户 token。
2. 调用 `POST /user/profile` 设置一个偏好（例如：美食 5 星、摄影 3 星、预算 1000-3000）。
3. 调用 `POST /user/ai/trip-plan`，展示生成的行程标题和 LLM 给出的「根据你的画像生成」解释字段。
4. 再调用 `GET /user/discover/recommend-trips`，展示列表中每条行程附带的推荐理由。
5. 更改画像（例如改成「亲子」「休闲」），重复步骤 3、4，对比两次返回的行程侧重点和推荐文案的差异。

> 这样可以在不引入向量库的情况下，自然地讲出「我们有用户画像、用行为数据做简单检索，再把结果作为上下文给大模型，形成了一个轻量级 RAG 味道的推荐系统」。


