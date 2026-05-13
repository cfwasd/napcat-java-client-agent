package com.napcat.agent.memory;

import com.napcat.agent.agent.NapCatAgent;
import com.napcat.agent.llm.ChatMessage;
import com.napcat.agent.session.Session;
import com.napcat.agent.session.SessionKey;
import com.napcat.core.message.MessageChain;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 记忆提取器。
 * 在 Agent 对话结束后，异步用 LLM 从完整会话历史中抽取结构化记忆，
 * 写入 MemoryStore 供后续对话检索。
 *
 * 首版策略：积累超过阈值条消息后触发提取，用 LLM 自身完成摘要。
 */
@Slf4j
public class MemoryExtractor {

    private final MemoryStore memoryStore;
    private final NapCatAgent agent;
    private final int extractThreshold;  // 累积多少条消息后触发提取
    private static final ObjectMapper mapper = new ObjectMapper();
    private final java.util.Map<SessionKey, java.util.concurrent.atomic.AtomicInteger> lastExtractedCounts = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_EXTRACT_TRACK_SIZE = 5000;

    public MemoryExtractor(MemoryStore memoryStore, NapCatAgent agent, int extractThreshold) {
        this.memoryStore = memoryStore;
        this.agent = agent;
        this.extractThreshold = extractThreshold;
    }

    /**
     * 检查是否需要提取记忆，如果是则异步执行。
     * 使用 CAS 防止同一 key 并发触发多次提取。
     * 在 Agent 回复后调用。
     */
    public void extractIfNeeded(SessionKey key, Session session) {
        // 防内存泄漏：超限后清空计数（下次会重新提取）
        if (lastExtractedCounts.size() > MAX_EXTRACT_TRACK_SIZE) {
            lastExtractedCounts.clear();
            log.warn("lastExtractedCounts cleared due to size limit");
        }

        int msgCount = (int) session.getHistory().stream()
                .filter(m -> !"system".equals(m.getRole()))
                .count();

        java.util.concurrent.atomic.AtomicInteger counter = lastExtractedCounts.computeIfAbsent(key, k -> new java.util.concurrent.atomic.AtomicInteger(0));
        int last = counter.get();
        int added = msgCount - last;

        if (added >= extractThreshold) {
            // CAS 原子更新：只有成功把计数从 last 改成 msgCount 的线程才触发提取
            if (counter.compareAndSet(last, msgCount)) {
                CompletableFuture.runAsync(() -> doExtract(key, session))
                        .whenComplete((v, ex) -> {
                            if (ex != null) {
                                log.warn("Memory extraction failed for {}: {}", key, ex.getMessage());
                                // 失败不回退计数，避免无限重试；下次 added 再次达标时会重试
                            }
                        });
            }
        }
    }

    /**
     * 同步提取并持久化记忆（用于 /new 等会话清除前）。
     * 阻塞等待 LLM 提取完成，最多等待 30 秒。
     *
     * @param key     会话键
     * @param session 待提取的会话
     */
    public void extractAndPersistSync(SessionKey key, Session session) {
        if (session.getHistory().isEmpty()) return;
        try {
            doExtract(key, session).get(30, java.util.concurrent.TimeUnit.SECONDS);
            int msgCount = (int) session.getHistory().stream()
                    .filter(m -> !"system".equals(m.getRole()))
                    .count();
            lastExtractedCounts.put(key, new java.util.concurrent.atomic.AtomicInteger(msgCount));
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("Memory extraction on /new timed out for {}", key);
        } catch (Exception e) {
            log.warn("Memory extraction on /new failed for {}: {}", key, e.getMessage());
        }
    }

    private CompletableFuture<Void> doExtract(SessionKey key, Session session) {
        try {
            String prompt = buildExtractionPrompt(session);
            if (prompt == null) return CompletableFuture.completedFuture(null);

            return agent.chat(key, prompt,
                    com.napcat.agent.agent.AgentConfig.builder()
                            .maxRounds(1)
                            .systemPrompt("你是一个信息提取助手。从对话中提取关于用户的关键事实、偏好和重要信息，以JSON数组格式返回。")
                            .internalCall(true)
                            .disableTools(true)
                            .build(),
                    null)
                    .thenAccept(result -> parseAndPersist(key, result))
                    .exceptionally(ex -> {
                        log.warn("Memory extraction failed for {}", key, ex.getMessage());
                        return null;
                    });

        } catch (Exception e) {
            log.error("Memory extraction error for {}", key, e);
            return CompletableFuture.failedFuture(e);
        }
    }

    private String buildExtractionPrompt(Session session) {
        StringBuilder sb = new StringBuilder();
        sb.append("从以下对话中提取关于用户的关键信息，以JSON数组格式返回：\n\n");
        sb.append("```json\n[\n");
        sb.append("  {\"type\": \"fact|preference|topic\", \"content\": \"...\", \"importance\": 1-5}\n");
        sb.append("]\n```\n\n");
        sb.append("规则：\n");
        sb.append("- fact: 用户陈述的事实（姓名、地点、经历等）\n");
        sb.append("- preference: 用户的偏好（喜欢/不喜欢什么）\n");
        sb.append("- topic: 讨论过的重要话题\n");
        sb.append("- importance: 1=次要, 3=一般, 5=非常重要\n");
        sb.append("- 只提取有长期价值的、将来可能再次提到的信息\n");
        sb.append("- 如果对话中没有任何值得长期记住的内容，返回空数组 []\n\n");
        sb.append("对话内容：\n");

        for (ChatMessage msg : session.getHistory()) {
            if ("user".equals(msg.getRole()) || "assistant".equals(msg.getRole())) {
                sb.append("[").append(msg.getRole()).append("]: ")
                        .append(msg.getContent() != null ? msg.getContent() : "")
                        .append("\n");
            }
        }

        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void parseAndPersist(SessionKey key, String json) {
        try {
            String jsonArray = extractJsonArray(json);
            if (jsonArray == null) return;

            List<java.util.Map<String, Object>> items = mapper.readValue(jsonArray,
                    new com.fasterxml.jackson.core.type.TypeReference<List<java.util.Map<String, Object>>>() {});

            for (var item : items) {
                String type = (String) item.getOrDefault("type", "summary");
                String content = (String) item.get("content");
                Object importanceObj = item.get("importance");
                int importance = 1;
                if (importanceObj instanceof Number) {
                    importance = ((Number) importanceObj).intValue();
                }
                if (content != null && !content.isBlank()) {
                    memoryStore.persist(key, content, type, importance);
                }
            }

            log.info("Extracted {} memories for {}", items.size(), key);
        } catch (Exception e) {
            log.warn("Failed to parse memory extraction result for {}", key, e);
        }
    }

    /**
     * 从文本中提取最外层的 JSON 数组（支持嵌套括号）。
     */
    private String extractJsonArray(String text) {
        int start = -1;
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0 && start >= 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }
}
