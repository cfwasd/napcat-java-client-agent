package com.napcat.agent.memory;

import com.napcat.agent.agent.AgentConfig;
import com.napcat.agent.agent.NapCatAgent;
import com.napcat.agent.session.SessionKey;
import com.napcat.agent.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 每日记忆归纳组件。
 * 由凌晨 1 点的定时任务触发，遍历所有用户的当天记忆，
 * 调用 LLM 进行归纳，将摘要存入 memory_summaries 表。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "napcat.memory", name = "enabled", havingValue = "true")
public class DailyMemorySummarizer {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final java.time.ZoneId UTC = java.time.ZoneId.of("UTC");
    private static final int MAX_CONCURRENT_SUMMARY = 4;

    @Autowired
    private SqliteMemoryStore memoryStore;

    @Autowired(required = false)
    @Lazy
    private NapCatAgent agent;

    @Autowired(required = false)
    private com.napcat.agent.session.SessionManager sessionManager;

    /**
     * 执行每日归纳。由定时任务回调。
     * 每天每个用户只归纳一次；归纳时携带最近历史摘要，保证内容随时间累积。
     */
    public void runDailySummary() {
        log.info("DailyMemorySummarizer started");

        // 第一步：将内存中所有活跃会话全量入库
        persistInMemorySessions();

        List<SessionKey> keys = memoryStore.listAllKeys();
        if (keys.isEmpty()) {
            log.info("No memory keys found, skipping daily summary");
            return;
        }

        String today = LocalDate.now(UTC).format(DATE_FMT);
        AtomicInteger summarized = new AtomicInteger(0);
        AtomicInteger skipped = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT_SUMMARY, r -> {
            Thread t = new Thread(r, "napcat-daily-summary");
            t.setDaemon(true);
            return t;
        });

        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (SessionKey key : keys) {
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        // 防重复：今天已归纳则跳过
                        if (memoryStore.hasSummaryToday(key, today)) {
                            log.debug("Summary already exists for {} on {}, skipping", key, today);
                            skipped.incrementAndGet();
                            return;
                        }

                        String todayMemories = memoryStore.getTodayMemories(key);
                        if (todayMemories == null || todayMemories.isBlank()) {
                            return;
                        }

                        // 获取最近 7 天历史摘要，与当天记忆合并归纳
                        List<String> recentSummaries = memoryStore.getRecentSummaries(key, 7);
                        String summary;
                        if (agent != null) {
                            summary = summarizeWithLLM(key, recentSummaries, todayMemories);
                        } else {
                            summary = summarizeSimple(todayMemories);
                        }

                        if (summary != null && !summary.isBlank()) {
                            memoryStore.summarize(key, today, summary);
                            summarized.incrementAndGet();
                        }
                    } catch (Exception e) {
                        log.error("Failed to summarize memories for {}: {}", key, e.getMessage());
                    }
                }, executor));
            }

            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.MINUTES);
            } catch (Exception e) {
                log.warn("Daily summary batch interrupted or timed out: {}", e.getMessage());
            }
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log.info("DailyMemorySummarizer completed: {} keys processed, {} summarized, {} skipped", keys.size(), summarized.get(), skipped.get());
    }

    /**
     * 将内存中所有活跃会话全量持久化到 memories 表。
     */
    private void persistInMemorySessions() {
        if (sessionManager == null) return;
        int persisted = 0;
        for (com.napcat.agent.session.SessionKey key : sessionManager.getAllKeys()) {
            com.napcat.agent.session.Session session = sessionManager.getIfPresent(key);
            if (session != null && !session.getHistory().isEmpty()) {
                String content = formatSessionHistory(session);
                if (!content.isBlank()) {
                    memoryStore.persistFullSession(key, content);
                    persisted++;
                }
            }
        }
        if (persisted > 0) {
            log.info("Persisted {} in-memory sessions before summarization", persisted);
        }
    }

    private String formatSessionHistory(com.napcat.agent.session.Session session) {
        return session.getFormattedHistory();
    }

    /**
     * 使用 LLM 归纳记忆。
     * 将最近历史摘要与当天新记忆合并，生成累积式摘要。
     */
    private String summarizeWithLLM(SessionKey key, List<String> recentSummaries, String todayMemories) {
        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append("请基于以下信息，生成一段关于用户的累积记忆摘要（150字以内）。\n\n");

            if (!recentSummaries.isEmpty()) {
                prompt.append("【历史已知信息】\n");
                for (String s : recentSummaries) {
                    prompt.append(s).append("\n");
                }
                prompt.append("\n");
            }

            prompt.append("【今日新记忆】\n").append(todayMemories).append("\n\n");
            prompt.append("要求：\n");
            prompt.append("1. 合并历史信息和今日新记忆，去重保留；\n");
            prompt.append("2. 如果今日没有新增关键信息，可保持历史摘要不变；\n");
            prompt.append("3. 输出一段连贯的用户画像摘要，不要分段；\n");
            prompt.append("4. 只返回摘要文本，不要解释。");

            String result = agent.chat(key, prompt.toString(),
                    AgentConfig.builder()
                            .maxRounds(1)
                            .systemPrompt("你是一个用户画像归纳助手。将历史摘要和当日新记忆合并为一段连贯、去重的累积摘要。")
                            .internalCall(true)
                            .disableTools(true)
                            .build(),
                    null)
                    .get(60, java.util.concurrent.TimeUnit.SECONDS);

            return (result != null && !result.isBlank()) ? result : null;
        } catch (Exception e) {
            log.warn("LLM summarization failed for {}, falling back to simple: {}", key, e.getMessage());
            return summarizeSimple(todayMemories);
        }
    }

    /**
     * 简单拼接归纳（无 LLM 时使用）。
     */
    private String summarizeSimple(String rawMemories) {
        if (rawMemories.length() <= 500) return rawMemories;
        int end = rawMemories.offsetByCodePoints(0, 500);
        return rawMemories.substring(0, end) + "…";
    }
}
