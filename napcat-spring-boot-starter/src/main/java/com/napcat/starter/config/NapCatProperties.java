package com.napcat.starter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "napcat")
public class NapCatProperties {

    private AdapterProperties adapter = new AdapterProperties();
    private BotProperties bot = new BotProperties();
    private LlmProperties llm = new LlmProperties();
    private AgentProperties agent = new AgentProperties();
    private MemoryProperties memory = new MemoryProperties();
    private SchedulerProperties scheduler = new SchedulerProperties();
    private CoreProperties core = new CoreProperties();

    @Data
    public static class AdapterProperties {
        /** 适配器类型：websocket-client / websocket-server / http-client / http-server */
        private String type = "websocket-client";
        private WsClientProperties websocketClient = new WsClientProperties();
        private WsServerProperties websocketServer = new WsServerProperties();
        private HttpClientProperties httpClient = new HttpClientProperties();
        private HttpServerProperties httpServer = new HttpServerProperties();
    }

    @Data
    public static class WsClientProperties {
        private String url = "ws://127.0.0.1:3001";
        private String token = "";
        private long reconnectInterval = 5000;
        private long heartInterval = 30000;
        private boolean debug = false;
    }

    @Data
    public static class WsServerProperties {
        private String host = "0.0.0.0";
        private int port = 3001;
        private String token = "";
        private boolean debug = false;
    }

    @Data
    public static class HttpClientProperties {
        private String url = "http://127.0.0.1:3000";
        private String token = "";
        private long timeout = 30000;
    }

    @Data
    public static class HttpServerProperties {
        private String host = "0.0.0.0";
        private int port = 8080;
        private String path = "/napcat/webhook";
        private String token = "";
        /** 反向 HTTP Client URL，用于主动调用 NapCat API。为空时仅被动接收上报。 */
        private String apiUrl = "";
        /** 反向 HTTP Client Token */
        private String apiToken = "";
        /** 反向 HTTP Client 超时（毫秒） */
        private long apiTimeout = 30000;
    }

    @Data
    public static class BotProperties {
        private long selfId = 0;
        private String commandPrefix = "";
        private boolean atMeTrigger = true;
        private boolean ignoreSelfMessage = true;
        private List<Long> superUsers = new ArrayList<>();
        /** 关键词唤醒列表。消息包含任一唤醒词时视为触发，无需 @。默认：["机器人", "bot"] */
        private List<String> wakeWords = new ArrayList<>(List.of("机器人", "bot"));
    }

    @Data
    public static class LlmProperties {
        private String provider = "openai";
        private ProviderConfig openai = new ProviderConfig();
        private ProviderConfig anthropic = new ProviderConfig();
        private ProviderConfig ollama = new ProviderConfig();
        private ProviderConfig custom = new ProviderConfig();
        /** 备用模型配置（当主模型失败时使用） */
        private FallbackProviderConfig fallback = new FallbackProviderConfig();
    }

    @Data
    public static class ProviderConfig {
        private String baseUrl;
        private String apiKey = "";
        private String model;
        private int maxTokens = 2000;
        private double temperature = 0.7;
        private long timeout = 60000;
    }

    @Data
    public static class FallbackProviderConfig {
        /** 是否启用备用模型 */
        private boolean enabled = false;
        /** 备用模型的 provider 类型：openai / anthropic / ollama / custom */
        private String provider = "openai";
        private String baseUrl;
        private String apiKey = "";
        private String model;
        private int maxTokens = 2000;
        private double temperature = 0.7;
        private long timeout = 60000;
    }

    @Data
    public static class AgentProperties {
        private boolean enabled = false;
        private int maxReactRounds = 5;
        private String systemPrompt = "你是一个友好的QQ机器人。\n" +
                "优先直接回答用户问题，只有当用户明确要求时才能调用工具。\n" +
                "Cron 表达式为 6 位格式「秒 分 时 日 月 周」，例：0 0 8 * * ? = 每天8点、0 30 14 * * ? = 每天14:30。\n" +
                "创建定时任务时务必：1) 提供标准 cron 表达式 2) 选择 action=ai_generate(需 prompt)或 send_message(需 replyText)。";
        private long timeoutPerRound = 30000;
        private long sessionTtl = 3600;
        /** 是否将工具调用过程发送到聊天 */
        private boolean showToolProcess = false;
        /** 会话历史最大消息条数，超出时自动截断（保留 system + 最近 N 条） */
        private int maxHistoryMessages = 50;
        /** 是否启用图片识别功能（如果LLM服务器无法访问QQ图片链接，建议关闭） */
        private boolean enableVision = true;
        /** 内置工具开关 */
        private BuiltinToolsProperties builtin = new BuiltinToolsProperties();
    }

    @Data
    public static class BuiltinToolsProperties {
        /** 联网搜索 (DuckDuckGo, 免费) */
        private ToolToggle webSearch = new ToolToggle(true);
        /** HTTP 抓取网页内容 */
        private ToolToggle fetchUrl = new ToolToggle(true);
        /** 日期时间查询 */
        private ToolToggle dateTime = new ToolToggle(true);
    }

    @Data
    public static class ToolToggle {
        private boolean enabled;

        public ToolToggle() {}
        public ToolToggle(boolean enabled) { this.enabled = enabled; }
    }

    @Data
    public static class MemoryProperties {
        /** 是否启用长久记忆 */
        private boolean enabled = false;
        /** 每次对话检索记忆条数 */
        private int maxResults = 5;
        /** 累积多少条消息触发提取 */
        private int extractThreshold = 20;
    }

    @Data
    public static class SchedulerProperties {
        /** 是否启用定时任务调度 */
        private boolean enabled = true;
        /** 轮询间隔（毫秒），默认 5 分钟 */
        private long pollIntervalMs = 5 * 60 * 1000;
        /** 提前注册窗口（毫秒），默认 5 分钟 */
        private long pollWindowMs = 5 * 60 * 1000;
    }

    @Data
    public static class CoreProperties {
        private ExecutorProperties eventExecutor = new ExecutorProperties();
        private String messagePostFormat = "array";
        private boolean syncEventProcessing = false;
        /** SQLite 数据库文件路径，默认 napcat_data/napcat.db */
        private String databasePath = "napcat_data/napcat.db";
    }

    @Data
    public static class ExecutorProperties {
        private int corePoolSize = 4;
        private int maxPoolSize = 16;
        private int queueCapacity = 1000;
    }
}
