# 快速开始

基于 NapCat OneBot11 协议的 Java Bot 开发框架，集成 AI Agent 能力。

---

## 前置要求

- JDK 17+
- Maven 3.8+
- 已部署并配置好的 NapCat（[安装指南](https://napneko.github.io/guide/start-install)）

## 快速开始

> **注意**：目前尚未发布到 Maven Central，请按以下步骤本地安装后开发。

### 1. 克隆并安装到本地

```bash
git clone https://github.com/cfwasd/napcat-java-client-agent.git
cd napcat-java-client-agent
mvn install -DskipTests
```

### 2. 配置 NapCat 连接

编辑 `napcat-admin/src/main/resources/application.yml`：

```yaml
napcat:
  adapter:
    type: websocket-client
    websocket-client:
      url: ws://127.0.0.1:3001
      token: ""
  bot:
    self-id: 123456789
```

### 3. 在 napcat-admin 中编写 Bot

直接在 `napcat-admin` 模块下创建你的 Bot 类即可：

```java
@Component
public class HelloBot {

    @OnGroupMessage
    @Command("/hello")
    public void hello(GroupMessageEvent event) {
        event.reply("Hello NapCat!");
    }

    @OnGroupMessage
    public void onGroup(GroupMessageEvent event) {
        if (event.getRawMessage().contains("在吗")) {
            event.reply("在的！");
        }
    }
}
```

然后运行 `napcat-admin` 的 `main` 方法启动 Bot。

更多示例见 `napcat-admin/src/main/java/com/napcat/admin/bot/` 目录。

---

### 在自己的项目中使用

如果你想在自己的项目中引入，先执行上面的 `mvn install`，然后在 `pom.xml` 中添加：

```xml
<dependency>
    <groupId>com.napcat</groupId>
    <artifactId>napcat-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

如需 Agent 能力，再添加一个 LLM Provider：

```xml
<!-- OpenAI 协议兼容（含 DeepSeek、通义千问等，支持多模态/vision） -->
<dependency>
    <groupId>com.napcat</groupId>
    <artifactId>napcat-llm-openai</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- 或 Anthropic Claude -->
<dependency>
    <groupId>com.napcat</groupId>
    <artifactId>napcat-llm-anthropic</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- 或 Ollama 本地模型 -->
<dependency>
    <groupId>com.napcat</groupId>
    <artifactId>napcat-llm-ollama</artifactId>
    <version>1.0.0</version>
</dependency>
```

---

## 进阶示例

### 接口式 Handler

```java
@Component
public class WeatherCommand implements CommandHandler {

    @Override
    public String getCommand() {
        return "/接口天气 {city}";
    }

    @Override
    public void handle(MessageEvent event, CommandArgs args) {
        String city = args.get("city");
        event.reply("【接口方式】" + city + " 天气晴朗");
    }
}
```

### 启用 AI Agent

```yaml
napcat:
  agent:
    enabled: true
    max-react-rounds: 5
  llm:
    provider: openai
    openai:
      base-url: https://api.openai.com/v1
      api-key: ${OPENAI_API_KEY}
      model: gpt-4o-mini
    fallback:
      enabled: false
      provider: openai
      model: gpt-4o-mini
  memory:
    enabled: false
    max-results: 5
    extract-threshold: 20
  scheduler:
    enabled: true
```

```java
@Component
public class AgentBot {

    @Autowired
    private NapCatAgent agent;

    @OnGroupMessage
    @MentionFilter
    public void onAt(GroupMessageEvent event) {
        // toAgentPrompt() 会保留图片、@等富文本信息，适合传给 Agent
        String prompt = event.getMessage().toAgentPrompt();
        agent.chat(event.getUserId(), event.getGroupId(), prompt)
            .thenAccept(event::reply);
    }
}
```

配置 `napcat.bot.at-me-trigger: true` 后，被 @ 或包含唤醒词时会自动走 Agent 流程，无需额外写 Handler。

---

## 模块架构

```
napcat-java/
├── napcat-parent                  # BOM，统一依赖版本
├── napcat-core                    # OneBot11 协议、通信适配器、事件路由
├── napcat-agent                   # LLM Agent 引擎、Tool 注册、ReAct 循环
├── napcat-llm-providers           # LLM 厂商实现
│   ├── napcat-llm-openai          # OpenAI 协议兼容（含多模态/vision、reasoning_content）
│   ├── napcat-llm-anthropic       # Claude
│   └── napcat-llm-ollama          # Ollama 本地模型
├── napcat-spring-boot-starter     # Spring Boot 自动配置
└── napcat-admin                   # 示例机器人应用
```
