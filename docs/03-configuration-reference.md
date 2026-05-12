# 配置参考

所有配置项通过 `application.yml` 或 `application.properties` 设置，前缀为 `napcat`。

配置类源码：`com.napcat.starter.config.NapCatProperties`

---

## 完整配置示例

```yaml
napcat:
  # ========== 通信适配器 ==========
  adapter:
    # 类型：websocket-client / websocket-server / http-client / http-server
    type: websocket-client

    websocket-client:
      url: ws://127.0.0.1:3001
      token: ""
      reconnect-interval: 5000          # 断线重连间隔，毫秒
      heart-interval: 30000             # 心跳间隔，毫秒
      debug: false                      # 打印原始帧

    websocket-server:
      host: 0.0.0.0
      port: 3001
      token: ""
      debug: false

    http-client:
      url: http://127.0.0.1:3000
      token: ""
      timeout: 30000                    # HTTP 请求超时，毫秒

    http-server:
      host: 0.0.0.0
      port: 8080
      token: ""
      path: /napcat/webhook             # 接收上报的路径
      api-url: ""                       # 反向 HTTP Client URL，用于主动调用 NapCat API
      api-token: ""                     # 反向 HTTP Client Token
      api-timeout: 30000                # 反向 HTTP Client 超时（毫秒）

  # ========== Bot 基础配置 ==========
  bot:
    self-id: 0                          # 当前机器人 QQ 号
    command-prefix: ""                  # 命令前缀，空字符串表示无前缀
    at-me-trigger: true                 # 被 @ 时是否自动触发 Agent（需 agent.enabled=true）
    ignore-self-message: true           # 是否过滤自己发的消息
    super-users: []                     # 超级管理员 QQ 号列表
    wake-words:                         # 关键词唤醒列表
      - "机器人"
      - "bot"

  # ========== LLM 配置 ==========
  llm:
    provider: openai                    # openai / anthropic / ollama / custom

    openai:
      base-url: https://api.openai.com/v1
      api-key: ""
      model: gpt-4o-mini                # 必须显式配置，无默认值
      max-tokens: 2000
      temperature: 0.7
      timeout: 60000

    anthropic:
      base-url: https://api.anthropic.com
      api-key: ""
      model: claude-sonnet-4-6          # 必须显式配置，无默认值
      max-tokens: 2000
      temperature: 0.7
      timeout: 60000

    ollama:
      base-url: http://localhost:11434
      api-key: ""
      model: llama3                     # 必须显式配置，无默认值
      timeout: 120000

    custom:
      base-url: ""                      # 任意兼容 OpenAI 协议的端点
      api-key: ""
      model: ""                         # 必须显式配置，无默认值
      max-tokens: 2000
      timeout: 60000

    fallback:                           # 备用模型（主模型失败时自动切换）
      enabled: false
      provider: openai                  # openai / anthropic / ollama / custom
      base-url: ""
      api-key: ""
      model: ""
      max-tokens: 2000
      temperature: 0.7
      timeout: 60000

  # ========== Agent 配置 ==========
  agent:
    enabled: false
    max-react-rounds: 5                 # ReAct 最大思考轮数
    system-prompt: "你是一个有用的 QQ 助手..."  # 内置默认值，可覆盖
    timeout-per-round: 30000            # 每轮 LLM 调用超时（毫秒）
    session-ttl: 3600                   # 会话过期时间，秒
    show-tool-process: false            # 是否将工具调用过程发送到聊天
    max-history-messages: 50            # 会话历史最大消息条数，超出时自动截断
    enable-vision: true                 # 是否启用图片识别（如果 LLM 服务器无法访问 QQ 图片链接，建议关闭）
    builtin:
      web-search:
        enabled: true                   # 联网搜索 (SearxNG)
      fetch-url:
        enabled: true                   # HTTP 抓取网页内容
      date-time:
        enabled: true                   # 日期时间查询

  # ========== 持久化记忆 ==========
  memory:
    enabled: false                      # 是否启用长期记忆
    max-results: 5                      # 每次对话检索记忆条数
    extract-threshold: 20               # 累积多少条消息后触发 LLM 提取
    test-data-enabled: false            # 启动时自动注入测试记忆数据（仅测试使用）

  # ========== 定时任务调度 ==========
  scheduler:
    enabled: true                       # 是否启用定时任务调度
    poll-interval-ms: 300000            # 轮询间隔（毫秒），默认 5 分钟
    poll-window-ms: 300000              # 提前注册窗口（毫秒），默认 5 分钟

  # ========== 高级配置 ==========
  core:
    event-executor:                     # 事件处理线程池
      core-pool-size: 4
      max-pool-size: 16
      queue-capacity: 1000
    message-post-format: array          # array / string，OneBot11 上报格式
    sync-event-processing: false        # 是否同步处理事件
    database-path: napcat_data/napcat.db  # SQLite 数据库文件路径
```

---

## 配置项详解

### napcat.adapter

控制与 NapCat 的通信方式，四选一。

#### type = `websocket-client`（默认、推荐）

主动连接 NapCat 的 WebSocket Server，双工通信，性能最好。

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `url` | String | `ws://127.0.0.1:3001` | NapCat WS 地址 |
| `token` | String | `""` | 鉴权 Token |
| `reconnect-interval` | long | `5000` | 断线重连间隔（ms） |
| `heart-interval` | long | `30000` | 心跳间隔（ms） |
| `debug` | boolean | `false` | 是否打印原始 WS 帧 |

**对应 NapCat 配置：**

```json
{
  "network": {
    "websocketServers": [{
      "enable": true,
      "port": 3001,
      "token": ""
    }]
  }
}
```

#### type = `websocket-server`

等待 NapCat 主动连接。适合多 NapCat 实例连接同一个 Bot 服务的场景。

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `host` | String | `"0.0.0.0"` | 监听地址 |
| `port` | int | `3001` | 监听端口 |
| `token` | String | `""` | 鉴权 Token |
| `debug` | boolean | `false` | 是否打印原始 WS 帧 |

#### type = `http-client`

主动调用 NapCat 的 HTTP API。

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `url` | String | `http://127.0.0.1:3000` | NapCat HTTP 地址 |
| `token` | String | `""` | 鉴权 Token |
| `timeout` | long | `30000` | HTTP 请求超时（ms） |

#### type = `http-server`

被动接收 NapCat 的 HTTP 上报。适合 Webhook 风格的部署。

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `host` | String | `"0.0.0.0"` | 监听地址 |
| `port` | int | `8080` | 监听端口 |
| `token` | String | `""` | 鉴权 Token |
| `path` | String | `"/napcat/webhook"` | 接收上报的 URL 路径 |
| `api-url` | String | `""` | 反向 HTTP Client URL，用于主动调用 NapCat API |
| `api-token` | String | `""` | 反向 HTTP Client Token |
| `api-timeout` | long | `30000` | 反向 HTTP Client 超时（ms） |

**注意：** 纯 HTTP Server 模式下无法主动调用 API，需配置 `api-url` 指向 NapCat 的 HTTP Server。

---

### napcat.bot

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `self-id` | long | `0` | 当前机器人 QQ 号，用于过滤自身消息和 @ 判断 |
| `command-prefix` | String | `""` | 命令前缀。空字符串表示无前缀，直接匹配命令模板开头 |
| `at-me-trigger` | boolean | `true` | 被 @ 时是否尝试走 Agent 流程（需 agent.enabled=true） |
| `ignore-self-message` | boolean | `true` | 是否忽略机器人自己发送的消息 |
| `super-users` | `List<long>` | `[]` | 超级管理员 QQ 号，用于 `Role.SUPERUSER` 判断 |
| `wake-words` | `List<String>` | `["机器人", "bot"]` | 关键词唤醒列表，消息包含任一唤醒词时视为触发 |

---

### napcat.llm

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `provider` | String | `openai` | LLM 提供商：`openai` / `anthropic` / `ollama` / `custom` |

各提供商专有配置：

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `base-url` | String | 见上方完整示例 | API 基础地址 |
| `api-key` | String | `""` | API Key（Ollama 可为空） |
| `model` | String | `null`（必须显式配置） | 模型名称 |
| `max-tokens` | int | `2000` | 最大生成 Token 数 |
| `temperature` | double | `0.7` | 采样温度 |
| `timeout` | long | `60000` | 单次请求超时（ms） |

**注意：** `model` 字段在所有 Provider 中均无内置默认值，必须显式配置。

---

### napcat.agent

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `enabled` | boolean | `false` | 是否启用 Agent 功能 |
| `max-react-rounds` | int | `5` | ReAct 循环最大轮数 |
| `system-prompt` | String | 内置提示 | Agent 系统提示词 |
| `timeout-per-round` | long | `30000` | 每轮 LLM 调用超时（ms） |
| `session-ttl` | long | `3600` | 会话上下文过期时间（秒） |
| `show-tool-process` | boolean | `false` | 是否将工具调用过程发送到聊天 |
| `max-history-messages` | int | `50` | 会话历史最大消息条数，超出时自动截断（保留 system + 最近 N 条） |

**内置工具开关：**

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `builtin.web-search.enabled` | boolean | `true` | 联网搜索 (DuckDuckGo) |
| `builtin.fetch-url.enabled` | boolean | `true` | HTTP 抓取网页内容 |
| `builtin.date-time.enabled` | boolean | `true` | 日期时间查询 |

---

### napcat.core

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `event-executor.core-pool-size` | int | `4` | 事件处理线程池核心线程数 |
| `event-executor.max-pool-size` | int | `16` | 最大线程数 |
| `event-executor.queue-capacity` | int | `1000` | 任务队列容量 |
| `message-post-format` | String | `"array"` | OneBot11 消息上报格式：`array` 或 `string` |
| `sync-event-processing` | boolean | `false` | 是否同步处理事件 |
| `database-path` | String | `"napcat_data/napcat.db"` | SQLite 数据库文件路径（定时任务、持久化记忆共用） |

---

### napcat.memory

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `enabled` | boolean | `false` | 是否启用长期记忆功能 |
| `max-results` | int | `5` | 每次对话时从记忆库检索的最大条数 |
| `extract-threshold` | int | `20` | 会话中累积多少条非 system 消息后，触发 LLM 异步提取记忆 |
| `test-data-enabled` | boolean | `false` | 启动时自动注入测试记忆数据，用于本地测试归纳和检索功能 |

启用后，Agent 会在对话中自动提取用户的关键事实、偏好和重要话题，存入 SQLite。新会话启动时会自动检索相关记忆并注入 system prompt。

**数据流：**
- 对话中 → `MemoryExtractor` 异步提取结构化记忆 → `memories` 表（type=fact/preference/topic）
- `/new` / 过期清理 / 程序关闭 → `persistFullSession` → `memories` 表（type=full_session，仅备份）
- 每日凌晨 1 点 → `DailyMemorySummarizer` 读取当天结构化记忆 → LLM 归纳 → `memory_summaries` 表
- 新会话启动 → 优先检索 `memory_summaries`（归纳摘要），其次检索 `memories`（碎片化记忆）

---

### napcat.scheduler

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `enabled` | boolean | `true` | 是否启用定时任务调度 |
| `poll-interval-ms` | long | `300000` | 轮询间隔（毫秒），默认 5 分钟 |
| `poll-window-ms` | long | `300000` | 提前注册窗口（毫秒），默认 5 分钟 |

Agent 可通过 `create_schedule` 工具创建 Cron 定时任务，任务持久化到 SQLite，重启后自动恢复。

---

### napcat.llm.fallback

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `enabled` | boolean | `false` | 是否启用备用模型 |
| `provider` | String | `openai` | 备用模型类型：`openai` / `anthropic` / `ollama` / `custom` |
| `base-url` | String | - | 备用模型 API 地址 |
| `api-key` | String | `""` | 备用模型 API Key |
| `model` | String | - | 备用模型名称 |
| `max-tokens` | int | `2000` | 最大 Token 数 |
| `temperature` | double | `0.7` | 采样温度 |
| `timeout` | long | `60000` | 请求超时（毫秒） |

主模型调用失败时，自动切换到备用模型重试一次。

---

## 多环境配置

Spring Boot 原生支持：

```yaml
# application-dev.yml
napcat:
  adapter:
    type: websocket-client
    websocket-client:
      url: ws://127.0.0.1:3001

# application-prod.yml
napcat:
  adapter:
    type: websocket-client
    websocket-client:
      url: ws://napcat.internal:3001
      token: ${NAPCAT_TOKEN}
  llm:
    openai:
      api-key: ${OPENAI_API_KEY}
```
