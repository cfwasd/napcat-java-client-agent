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
    private CoreProperties core = new CoreProperties();

    @Data
    public static class AdapterProperties {
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
    }

    @Data
    public static class BotProperties {
        private long selfId = 0;
        private String commandPrefix = "";
        private boolean atMeTrigger = true;
        private boolean ignoreSelfMessage = true;
        private List<Long> superUsers = new ArrayList<>();
    }

    @Data
    public static class LlmProperties {
        private String provider = "openai";
        private ProviderConfig openai = new ProviderConfig();
        private ProviderConfig anthropic = new ProviderConfig();
        private ProviderConfig ollama = new ProviderConfig();
        private ProviderConfig custom = new ProviderConfig();
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
    public static class AgentProperties {
        private boolean enabled = false;
        private int maxReactRounds = 5;
        private String systemPrompt = "";
        private long timeoutPerRound = 30000;
        private long sessionTtl = 3600;
    }

    @Data
    public static class CoreProperties {
        private ExecutorProperties eventExecutor = new ExecutorProperties();
        private String messagePostFormat = "array";
        private boolean syncEventProcessing = false;
    }

    @Data
    public static class ExecutorProperties {
        private int corePoolSize = 4;
        private int maxPoolSize = 16;
        private int queueCapacity = 1000;
    }
}
