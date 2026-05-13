package com.napcat.agent.agent;

import lombok.Builder;
import lombok.Data;

import java.util.function.Consumer;

@Data
@Builder
public class AgentConfig {
    @Builder.Default
    private int maxRounds = 5;
    private String systemPrompt;
    @Builder.Default
    private long timeoutPerRound = 30000;
    /**
     * 是否将工具调用/结果通过 toolProcessConsumer 回传。
     * 调用方可通过 {@link NapCatAgent#chat(long, long, String, AgentConfig, Consumer)}
     * 传入 consumer 来接收工具执行过程。
     */
    @Builder.Default
    private boolean showToolProcess = false;
    /**
     * 消息确认回调。在用户消息写入 session 之后、ReAct 循环启动之前调用。
     * 用于立即回复表情/赞等表示"已收到，正在处理"，提升用户体验。
     * <p>
     * 典型用法：
     * <pre>{@code
     *   AgentConfig config = AgentConfig.builder()
     *       .ackCallback(() -> event.reply(MessageChain.ofFace(277)))
     *       .build();
     * }</pre>
     */
    private Runnable ackCallback;
    /** 是否启用长期记忆注入 */
    @Builder.Default
    private boolean memoryEnabled = false;
    /** 每次检索的记忆最大条数 */
    @Builder.Default
    private int memoryMaxResults = 5;
    /** 累积多少条消息后触发记忆提取（0=不自动提取） */
    @Builder.Default
    private int memoryExtractThreshold = 20;
    /** 内部调用标志，为 true 时跳过记忆注入和提取，避免递归（如 DailyMemorySummarizer 调用 LLM） */
    @Builder.Default
    private boolean internalCall = false;
    /** 禁用工具调用，用于内部任务（如记忆归纳）避免触发不必要的工具链 */
    @Builder.Default
    private boolean disableTools = false;
}
