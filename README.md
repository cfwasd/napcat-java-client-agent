# NapCat Java SDK

基于 [NapCat](https://github.com/NapNeko/NapCatQQ) OneBot11 协议的 Java Bot 开发框架，集成 AI Agent 能力，支持注解驱动与接口驱动两种编程模型。

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

更多示例见 [napcat-admin/src/main/java/com/napcat/admin/bot/](napcat-admin/src/main/java/com/napcat/admin/bot/)。

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

---

## 文档

在线文档站：https://cfwasd.github.io/napcat-java-client-agent/

完整文档见 [docs/](docs/) 目录：

| 文档 | 内容 |
|------|------|
| [快速开始](docs/01-quick-start.md) | 环境准备、依赖引入、第一个 Bot、启用 Agent |
| [编程模型](docs/02-programming-model.md) | 所有注解、接口定义、返回值处理、路由优先级 |
| [配置参考](docs/03-configuration-reference.md) | 完整配置项、适配器配置、多环境配置 |
| [事件与消息](docs/04-event-message-model.md) | 事件体系、MessageChain、Sender、API 列表 |
| [通信适配器](docs/05-adapter-guide.md) | 四种适配器对比、配置示例、混合模式 |
| [Agent 指南](docs/06-agent-guide.md) | ReAct 循环、Tool 注册、会话管理、多模态、LLM Provider |
| [内部架构](docs/07-internal-architecture.md) | 模块职责、启动流程、线程模型、扩展点 |

---

## 功能特性

- **全协议通信**：支持 HTTP Server / Client、WebSocket Server / Client 四种 NapCat 通信方式
- **双编程模型**：注解式（`@OnGroupMessage`、`@Command`）与接口式（`EventHandler`、`CommandHandler`）并存
- **OneBot11 完整模型**：消息链（MessageChain）、事件、API 请求/响应全覆盖；支持 array / string（CQ 码）双格式上报解析
- **AI Agent 引擎**：内置 ReAct 轻量循环（默认最多 5 轮），支持 Function Calling / Tool Use
- **多模态支持**：`MessageChain.toAgentPrompt()` 保留图片、语音、视频等富文本标记；OpenAI Provider 自动将 `[图片:url]` 提取为 `image_url` 多模态消息
- **多 LLM 后端**：OpenAI 协议兼容（含多模态/vision）、Anthropic Claude、Ollama 本地模型、自定义 OpenAI 端点
- **Spring Boot 开箱即用**：`napcat-spring-boot-starter` 自动配置，高度可配置化
- **组合注解**：支持自定义元注解，如 `@OnGroupAt`、`@AdminCommand`
- **关键词唤醒**：消息包含配置唤醒词时自动触发，无需 @
- **持久化长期记忆**：SQLite 存储用户关键信息，跨会话自动检索注入上下文，支持每日 LLM 自动归纳
- **定时任务调度**：Agent 可通过工具创建 Cron 定时任务，支持 AI 生成内容或固定文本推送，持久化到 SQLite
- **LLM 备用模型**：主模型失败时自动切换到备用模型，支持 openai / anthropic / ollama / custom
- **会话上下文**：按用户 ID + 群号隔离的会话管理，支持过期自动清理与手动重置

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

---

## 相关链接

- [NapCatQQ 官方文档](https://napneko.github.io/guide/start-install)
- [NapCat API 文档 (Apifox)](https://napcat.apifox.cn/llms.txt)
- [本项目 GitHub](https://github.com/cfwasd/napcat-java-client-agent)

## License

MIT
