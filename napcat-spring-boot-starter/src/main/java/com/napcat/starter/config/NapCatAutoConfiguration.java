package com.napcat.starter.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.napcat.agent.agent.NapCatAgent;
import com.napcat.agent.llm.LlmProvider;
import com.napcat.agent.session.SessionManager;
import com.napcat.agent.tool.ToolRegistry;
import com.napcat.core.adapter.*;
import com.napcat.core.api.NapCatApi;
import com.napcat.core.config.BotProperties;
import com.napcat.core.handler.EventDispatcher;
import com.napcat.core.handler.HandlerRegistry;
import com.napcat.core.scheduler.*;
import com.napcat.agent.memory.*;
import com.napcat.agent.scheduler.TaskExecutor;
import com.napcat.agent.scheduler.ScheduleTool;
import com.napcat.starter.adapter.HttpServerAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(NapCatProperties.class)
@ComponentScan("com.napcat")
public class NapCatAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper napcatObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public BotProperties botProperties(NapCatProperties props) {
        BotProperties bp = new BotProperties();
        bp.setSelfId(props.getBot().getSelfId());
        bp.setCommandPrefix(props.getBot().getCommandPrefix());
        bp.setAtMeTrigger(props.getBot().isAtMeTrigger());
        bp.setIgnoreSelfMessage(props.getBot().isIgnoreSelfMessage());
        bp.setSuperUsers(props.getBot().getSuperUsers());
        bp.setWakeWords(props.getBot().getWakeWords());
        return bp;
    }

    // ================================================================
    // Adapter
    // ================================================================

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "napcat.adapter", name = "type", havingValue = "websocket-client", matchIfMissing = true)
    public BotAdapter wsClientBotAdapter(NapCatProperties props, ObjectMapper mapper) {
        var c = props.getAdapter().getWebsocketClient();
        return new WsClientAdapter(c.getUrl(), c.getToken(), c.getReconnectInterval(), mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "napcat.adapter", name = "type", havingValue = "http-client")
    public BotAdapter httpClientBotAdapter(NapCatProperties props, ObjectMapper mapper) {
        var c = props.getAdapter().getHttpClient();
        return new HttpClientAdapter(c.getUrl(), c.getToken(), c.getTimeout(), mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "napcat.adapter", name = "type", havingValue = "websocket-server")
    public BotAdapter wsServerBotAdapter(NapCatProperties props, ObjectMapper mapper) {
        var c = props.getAdapter().getWebsocketServer();
        return new WsServerAdapter(c.getHost(), c.getPort(), c.getToken(), mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "napcat.adapter", name = "type", havingValue = "http-server")
    public BotAdapter httpServerBotAdapter(NapCatProperties props, ObjectMapper mapper) {
        var c = props.getAdapter().getHttpServer();
        return new HttpServerAdapter(mapper,
                c.getPath(), c.getToken(),
                c.getApiUrl(), c.getApiToken(), c.getApiTimeout());
    }

    // ================================================================
    // API + Router + Dispatcher
    // ================================================================

    @Bean
    @ConditionalOnMissingBean
    public NapCatApi napCatApi(List<BotAdapter> adapters, ObjectMapper mapper) {
        return new NapCatApi(adapters.isEmpty() ? null : adapters.get(0), mapper, 30000);
    }

    @Bean
    @ConditionalOnMissingBean
    public MessageRouter messageRouter(ObjectMapper mapper, NapCatApi api) {
        return new MessageRouter(mapper, api);
    }

    @Bean
    @ConditionalOnMissingBean
    public HandlerRegistry handlerRegistry(BotProperties botProperties) {
        return new HandlerRegistry(botProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    public EventDispatcher eventDispatcher(HandlerRegistry registry, BotProperties botProperties,
                                           NapCatApi api, NapCatProperties props) {
        var execProps = props.getCore().getEventExecutor();
        boolean sync = props.getCore().isSyncEventProcessing();
        Executor executor = new ThreadPoolExecutor(
                execProps.getCorePoolSize(),
                execProps.getMaxPoolSize(),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(execProps.getQueueCapacity()),
                r -> {
                    Thread t = new Thread(r, "napcat-event-pool");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return new EventDispatcher(registry, botProperties, api, sync, executor);
    }

    // ================================================================
    // Agent
    // ================================================================

    @Bean
    @ConditionalOnProperty(prefix = "napcat.agent", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry(ApplicationContext ctx) {
        ToolRegistry registry = new ToolRegistry();
        // 扫描所有 Bean，检查是否有方法标注了 @Tool（@Tool 是 METHOD 级别注解）
        for (String name : ctx.getBeanDefinitionNames()) {
            try {
                Object bean = ctx.getBean(name);
                Class<?> clazz = bean.getClass();
                // 跳过 CGLIB 代理，取原始类
                while (clazz.getName().contains("$$")) {
                    clazz = clazz.getSuperclass();
                }
                for (java.lang.reflect.Method method : clazz.getDeclaredMethods()) {
                    if (method.isAnnotationPresent(com.napcat.core.annotation.Tool.class)) {
                        registry.register(bean);
                        break;  // 一个 bean 只注册一次
                    }
                }
            } catch (Exception ignored) {
                // 跳过无法获取的 bean（如某些框架内部 bean）
            }
        }
        log.info("ToolRegistry initialized with {} tools", registry.getSchemas().size());
        return registry;
    }

    @Bean
    @ConditionalOnProperty(prefix = "napcat.agent", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public SessionManager sessionManager(NapCatProperties props) {
        return new SessionManager(props.getAgent().getSessionTtl(),
                props.getAgent().getMaxHistoryMessages());
    }

    @Bean
    @ConditionalOnProperty(prefix = "napcat.agent", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public NapCatAgent napCatAgent(ObjectProvider<LlmProvider> llmProvider, ToolRegistry toolRegistry,
                                    SessionManager sessionManager, NapCatProperties props,
                                    ApplicationContext ctx) {
        LlmProvider provider = llmProvider.getIfAvailable();
        if (provider == null) {
            throw new IllegalStateException("No LlmProvider bean found. Please add a provider dependency like napcat-llm-openai.");
        }

        // 如果启用了备用模型，创建 FallbackLlmProvider
        if (props.getLlm().getFallback().isEnabled()) {
            LlmProvider fallbackProvider = createFallbackProvider(props);
            if (fallbackProvider != null) {
                provider = new com.napcat.agent.llm.FallbackLlmProvider(provider, fallbackProvider, true);
                log.info("✅ Fallback LLM provider enabled: primary -> {}", 
                        props.getLlm().getFallback().getProvider());
            }
        }

        return new NapCatAgent(provider, toolRegistry, sessionManager,
                ctx.getBeanProvider(MemoryStore.class).getIfAvailable(),
                ctx.getBeanProvider(MemoryExtractor.class).getIfAvailable(),
                props.getAgent().getSystemPrompt(), props.getAgent().getMaxReactRounds(),
                props.getAgent().isEnableVision());
    }

    /**
     * 创建备用 LLM Provider
     */
    private LlmProvider createFallbackProvider(NapCatProperties props) {
        var fallback = props.getLlm().getFallback();
        String providerType = fallback.getProvider().toLowerCase();
        
        try {
            return switch (providerType) {
                case "openai", "custom" -> createOpenAiProvider(fallback);
                case "anthropic" -> createAnthropicProvider(fallback);
                case "ollama" -> createOllamaProvider(fallback);
                default -> {
                    log.warn("❌ Unknown fallback provider type: {}", providerType);
                    yield null;
                }
            };
        } catch (Exception e) {
            log.error("❌ Failed to create fallback LLM provider: {}", providerType, e);
            return null;
        }
    }

    /**
     * 通过反射创建 OpenAI Provider（避免循环依赖）
     */
    private LlmProvider createOpenAiProvider(NapCatProperties.FallbackProviderConfig config) {
        try {
            Class<?> clazz = Class.forName("com.napcat.llm.openai.OpenAiProvider");
            return (LlmProvider) clazz.getConstructor(
                    String.class, String.class, String.class, 
                    int.class, double.class, long.class
            ).newInstance(
                    config.getBaseUrl(),
                    config.getApiKey(),
                    config.getModel(),
                    config.getMaxTokens(),
                    config.getTemperature(),
                    config.getTimeout()
            );
        } catch (ClassNotFoundException e) {
            log.error("OpenAI provider class not found. Make sure napcat-llm-openai is in classpath.");
            return null;
        } catch (Exception e) {
            log.error("Failed to create OpenAI provider", e);
            return null;
        }
    }

    /**
     * 通过反射创建 Anthropic Provider（避免循环依赖）
     */
    private LlmProvider createAnthropicProvider(NapCatProperties.FallbackProviderConfig config) {
        try {
            Class<?> clazz = Class.forName("com.napcat.llm.anthropic.AnthropicProvider");
            return (LlmProvider) clazz.getConstructor(
                    String.class, String.class, String.class,
                    int.class, double.class, long.class
            ).newInstance(
                    config.getBaseUrl(),
                    config.getApiKey(),
                    config.getModel(),
                    config.getMaxTokens(),
                    config.getTemperature(),
                    config.getTimeout()
            );
        } catch (ClassNotFoundException e) {
            log.error("Anthropic provider class not found. Make sure napcat-llm-anthropic is in classpath.");
            return null;
        } catch (Exception e) {
            log.error("Failed to create Anthropic provider", e);
            return null;
        }
    }

    /**
     * 通过反射创建 Ollama Provider（避免循环依赖）
     */
    private LlmProvider createOllamaProvider(NapCatProperties.FallbackProviderConfig config) {
        try {
            Class<?> clazz = Class.forName("com.napcat.llm.ollama.OllamaProvider");
            return (LlmProvider) clazz.getConstructor(
                    String.class, String.class, long.class
            ).newInstance(
                    config.getBaseUrl(),
                    config.getModel(),
                    config.getTimeout()
            );
        } catch (ClassNotFoundException e) {
            log.error("Ollama provider class not found. Make sure napcat-llm-ollama is in classpath.");
            return null;
        } catch (Exception e) {
            log.error("Failed to create Ollama provider", e);
            return null;
        }
    }

    // ================================================================
    // Database
    // ================================================================

    @Bean
    @ConditionalOnMissingBean
    public DbManager dbManager(NapCatProperties props) {
        DbManager db = new DbManager(props.getCore().getDatabasePath());
        db.init();
        return db;
    }

    @Bean
    @ConditionalOnMissingBean
    public MigrationManager migrationManager(DbManager dbManager) {
        MigrationManager mm = new MigrationManager(dbManager);
        // 注册 schedules + memories 表
        mm.register(1, "create schedules table", ScheduleStore.ddl());
        mm.register(2, "create memories table", SqliteMemoryStore.memoriesDdl());
        // 为已有任务设置默认 created_by（如果为空）
        mm.register(3, "set default created_by for existing schedules", 
                "UPDATE schedules SET created_by = 0 WHERE created_by IS NULL");
        // 为 schedules 表添加 is_recurring 字段
        mm.register(4, "add is_recurring column to schedules",
                "ALTER TABLE schedules ADD COLUMN is_recurring INTEGER DEFAULT 1");
        // 创建 memory_summaries 表（每日归纳）
        mm.register(5, "create memory_summaries table", SqliteMemoryStore.summariesDdl());
        // 添加索引优化查询
        mm.register(6, "create memory indexes",
                "CREATE INDEX IF NOT EXISTS idx_memories_user_group ON memories(user_id, group_id);" +
                "CREATE INDEX IF NOT EXISTS idx_memories_created ON memories(created_at);" +
                "CREATE INDEX IF NOT EXISTS idx_summaries_user_group_date ON memory_summaries(user_id, group_id, summary_date);");
        mm.migrate();
        return mm;
    }

    @Bean
    @ConditionalOnMissingBean
    public ScheduleStore scheduleStore(DbManager dbManager) {
        return new ScheduleStore(dbManager);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "napcat.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
    public TimerWheel timerWheel() {
        return new TimerWheel();
    }

    @Bean(name = "napcatTaskExecutor")
    @ConditionalOnMissingBean(name = "napcatTaskExecutor")
    @ConditionalOnProperty(prefix = "napcat.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
    public TaskExecutor taskExecutor(NapCatApi api, ObjectProvider<NapCatAgent> agentProvider,
                                      ObjectProvider<DailyMemorySummarizer> summarizerProvider) {
        return new TaskExecutor(api, agentProvider.getIfAvailable(), summarizerProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "napcat.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ScheduleTool scheduleTool(ScheduleStore store, ObjectProvider<SchedulePoller> pollerProvider) {
        return new ScheduleTool(store, pollerProvider.getIfAvailable());
    }

    // ================================================================
    // Memory
    // ================================================================

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "napcat.memory", name = "enabled", havingValue = "true")
    public MemoryStore memoryStore(DbManager dbManager) {
        return new SqliteMemoryStore(dbManager);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "napcat.memory", name = "enabled", havingValue = "true")
    public MemoryExtractor memoryExtractor(MemoryStore memoryStore, NapCatAgent agent, NapCatProperties props) {
        return new MemoryExtractor(memoryStore, agent, props.getMemory().getExtractThreshold());
    }

    // ================================================================
    // Scheduler
    // ================================================================

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "napcat.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SchedulePoller schedulePoller(ScheduleStore store, TimerWheel timerWheel,
                                          @org.springframework.beans.factory.annotation.Qualifier("napcatTaskExecutor") TaskExecutor executor, NapCatProperties props) {
        SchedulePoller poller = new SchedulePoller(store, timerWheel, executor::execute,
                props.getScheduler().getPollIntervalMs(),
                props.getScheduler().getPollWindowMs());
        return poller;
    }

    // ================================================================
    // 后处理器 + 生命周期
    // ================================================================

    @Bean
    public NapCatBeanPostProcessor napCatBeanPostProcessor(HandlerRegistry registry, ApplicationContext ctx) {
        return new NapCatBeanPostProcessor(registry, ctx);
    }

    @Bean
    public NapCatLifecycle napCatLifecycle(List<BotAdapter> adapters, EventDispatcher dispatcher,
                                           NapCatApi api, HandlerRegistry registry,
                                           MessageRouter messageRouter,
                                           BotProperties botProperties,
                                           ObjectProvider<NapCatAgent> agentProvider,
                                           ApplicationContext ctx) {
        return new NapCatLifecycle(adapters, dispatcher, api, registry, messageRouter,
                botProperties, agentProvider, ctx);
    }
}