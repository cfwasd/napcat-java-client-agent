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
    private static final int MAX_CONCURRENT_SUMMARY = 4;

    @Autowired
    private SqliteMemoryStore memoryStore;

    @Autowired(required = false)
    @Lazy
    private NapCatAgent agent;

    /**
     * 执行每日归纳。由定时任务回调。
     * 遍历所有有记忆的用户，将当天记忆归纳为摘要。
     */
    public void runDailySummary() {
        log.info("DailyMemorySummarizer started");
        List<SessionKey> keys = memoryStore.listAllKeys();
        if (keys.isEmpty()) {
            log.info("No memory keys found, skipping daily summary");
            return;
        }

        String today = LocalDate.now().format(DATE_FMT);
        AtomicInteger summarized = new AtomicInteger(0);

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
                        String todayMemories = memoryStore.getTodayMemories(key);
                        if (todayMemories == null || todayMemories.isBlank()) {
                            return;
                        }

                        // 如果有 Agent，用 LLM 归纳；否则用简单拼接
                        String summary;
                        if (agent != null) {
                            summary = summarizeWithLLM(key, todayMemories);
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

        log.info("DailyMemorySummarizer completed: {} keys processed, {} summarized", keys.size(), summarized.get());
    }

    /**
     * 使用 LLM 归纳记忆。
     */
    private String summarizeWithLLM(SessionKey key, String rawMemories) {
        try {
            String prompt = "请将以下用户今日的对话记忆归纳为一段简洁的摘要（100字以内），" +
                    "保留关键事实、偏好和重要话题：\n\n" + rawMemories;

            String result = agent.chat(key, prompt,
                    AgentConfig.builder()
                            .maxRounds(1)
                            .systemPrompt("你是一个信息归纳助手。将碎片化记忆归纳为连贯的摘要，保留关键信息。")
                            .build(),
                    null)
                    .get(60, java.util.concurrent.TimeUnit.SECONDS);

            return (result != null && !result.isBlank()) ? result : null;
        } catch (Exception e) {
            log.warn("LLM summarization failed for {}, falling back to simple: {}", key, e.getMessage());
            return summarizeSimple(rawMemories);
        }
    }

    /**
     * 简单拼接归纳（无 LLM 时使用）。
     */
    private String summarizeSimple(String rawMemories) {
        // 取前 500 字符作为简单摘要
        if (rawMemories.length() > 500) {
            return rawMemories.substring(0, 500) + "…";
        }
        return rawMemories;
    }
}
