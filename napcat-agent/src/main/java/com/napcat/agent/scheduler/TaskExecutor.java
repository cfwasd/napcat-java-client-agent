package com.napcat.agent.scheduler;

import com.napcat.agent.agent.NapCatAgent;
import com.napcat.agent.memory.DailyMemorySummarizer;
import com.napcat.core.api.NapCatApi;
import com.napcat.core.message.MessageChain;
import com.napcat.core.scheduler.ScheduleStore.ScheduleEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 定时任务执行器。
 * 从 SchedulePoller 的回调中触发，根据 schedule 的 action 字段选择执行路径：
 * - send_message：直接用 NapCatApi 发送固定文本（0 token）
 * - ai_generate：调用 NapCatAgent 生成动态内容后发送
 * - custom：系统级自定义任务（如每日记忆归纳）
 * 
 * 群聊场景下会自动 @ 任务创建者。
 */
@Slf4j
public class TaskExecutor {

    private final NapCatApi api;
    private final NapCatAgent agent;
    private final ObjectProvider<DailyMemorySummarizer> summarizerProvider;

    public TaskExecutor(NapCatApi api, NapCatAgent agent,
                        ObjectProvider<DailyMemorySummarizer> summarizerProvider) {
        this.summarizerProvider = summarizerProvider;
        this.api = api;
        this.agent = agent;
    }

    /**
     * 执行一个定时任务。
     */
    public void execute(ScheduleEntry entry) {
        if (!entry.isEnabled()) {
            log.debug("Skipping disabled schedule: {}", entry.getId());
            return;
        }

        String action = entry.getAction() != null ? entry.getAction() : "send_message";

        try {
            switch (action) {
                case "send_message" -> executeSendMessage(entry);
                case "ai_generate" -> executeAiGenerate(entry);
                case "custom" -> executeCustom(entry);
                default -> log.warn("Unknown schedule action '{}' for {}", action, entry.getId());
            }
        } catch (Exception e) {
            log.error("Schedule execution failed: id={}, name={}", entry.getId(), entry.getName(), e);
        }
    }

    private void executeSendMessage(ScheduleEntry entry) {
        String text = entry.getReplyText();
        if (text == null || text.isBlank()) {
            log.warn("Schedule {} has no replyText, using task name as message", entry.getId());
            // 如果没有回复文本，使用任务名称作为默认消息
            text = "⏰ 定时任务提醒：" + entry.getName();
        }

        // 构建消息链
        MessageChain msg;
        boolean isGroup = "group".equals(entry.getTargetType());
        Long createdBy = entry.getCreatedBy();
        
        if (isGroup && createdBy != null && createdBy > 0) {
            // 群聊场景：艾特创建者
            msg = new MessageChain()
                    .at(createdBy)
                    .text(" " + text);
            log.info("Sent scheduled message with mention: id={}, target={}/{}, creator={}, text={}", 
                    entry.getId(), entry.getTargetType(), entry.getTargetId(), createdBy,
                    text.length() > 50 ? text.substring(0, 50) + "..." : text);
        } else {
            // 私聊或非群聊场景：直接发送文本
            msg = MessageChain.ofText(text);
            log.info("Sent scheduled message: id={}, target={}/{}, text={}", 
                    entry.getId(), entry.getTargetType(), entry.getTargetId(), 
                    text.length() > 50 ? text.substring(0, 50) + "..." : text);
        }
        
        if ("private".equals(entry.getTargetType())) {
            api.sendPrivateMessage(entry.getTargetId(), msg);
        } else {
            api.sendGroupMessage(entry.getTargetId(), msg);
        }
    }

    private void executeAiGenerate(ScheduleEntry entry) {
        if (agent == null) {
            log.warn("NapCatAgent not available, falling back to replyText for {}", entry.getId());
            if (entry.getReplyText() != null && !entry.getReplyText().isBlank()) {
                executeSendMessage(entry);
            }
            return;
        }

        String prompt = entry.getPrompt();
        if (prompt == null || prompt.isBlank()) {
            log.warn("Schedule {} has no prompt for ai_generate", entry.getId());
            return;
        }

        // 支持 {time} 占位符替换
        String currentTime = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        prompt = prompt.replace("{time}", currentTime);

        // 构建针对定时任务的系统提示词
        String scheduleSystemPrompt = String.format(
            "你是一个定时任务助手。当前时间是 %s。\n" +
            "请根据用户的提醒内容，生成一段友好、自然的消息。\n" +
            "要求：\n" +
            "1. 语气亲切自然，像朋友间的提醒\n" +
            "2. 可以适当添加表情符号增加亲和力\n" +
            "3. 简洁明了，不要过长（50-100字为宜）\n" +
            "4. 如果是群聊消息，不需要特别称呼\n" +
            "5. 直接返回消息内容，不要添加解释",
            currentTime
        );

        long targetId = entry.getTargetId();
        boolean isPrivate = "private".equals(entry.getTargetType());

        String finalPrompt = prompt;
        String finalSystemPrompt = scheduleSystemPrompt;
        
        // 使用自定义配置调用 Agent
        com.napcat.agent.agent.AgentConfig config = com.napcat.agent.agent.AgentConfig.builder()
                .systemPrompt(finalSystemPrompt)
                .maxRounds(1)  // 只需要一轮对话
                .build();
        
        agent.chat(isPrivate ? targetId : 0, isPrivate ? 0 : targetId, finalPrompt, config, null)
                .thenAccept(reply -> {
                    if (reply != null && !reply.isBlank()) {
                        // 构建消息链
                        com.napcat.core.message.MessageChain msg;
                        Long createdBy = entry.getCreatedBy();
                        
                        if (!isPrivate && createdBy != null && createdBy > 0) {
                            // 群聊场景：艾特创建者
                            msg = new com.napcat.core.message.MessageChain()
                                    .at(createdBy)
                                    .text(" " + reply);
                            log.info("Sent AI generated message with mention: id={}, target={}, creator={}", 
                                    entry.getId(), targetId, createdBy);
                        } else {
                            // 私聊场景：直接发送
                            msg = com.napcat.core.message.MessageChain.ofText(reply);
                            log.info("Sent AI generated message: id={}, target={}", entry.getId(), targetId);
                        }
                        
                        if (isPrivate) {
                            api.sendPrivateMessage(targetId, msg);
                        } else {
                            api.sendGroupMessage(targetId, msg);
                        }
                    }
                })
                .exceptionally(ex -> {
                    log.error("AI generate failed for schedule {}", entry.getId(), ex);
                    return null;
                });
    }

    private void executeCustom(ScheduleEntry entry) {
        if ("每日记忆归纳".equals(entry.getName())) {
            DailyMemorySummarizer summarizer = summarizerProvider.getIfAvailable();
            if (summarizer != null) {
                summarizer.runDailySummary();
            } else {
                log.warn("DailyMemorySummarizer not available, skipping daily summary");
            }
        }
    }
}