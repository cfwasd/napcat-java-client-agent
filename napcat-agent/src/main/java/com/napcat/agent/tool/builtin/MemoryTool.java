package com.napcat.agent.tool.builtin;

import com.napcat.agent.memory.MemoryStore;
import com.napcat.agent.memory.MemoryStore.CandidateMemory;
import com.napcat.agent.session.SessionKey;
import com.napcat.agent.tool.ToolRegistry;
import com.napcat.core.annotation.Tool;
import com.napcat.core.annotation.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 记忆检索工具。
 * 让 Agent 在对话过程中主动查询用户的历史记忆。
 *
 * 检索策略：
 * 1. 有时间范围：先按日期范围拉取候选记忆；若该范围无记录，fallback 到全量最近记忆
 * 2. 无时间范围：直接拉取该用户最近的全量记忆，不做关键词硬过滤
 * 3. 返回的原始记忆交由 LLM 做语义分析，工具层只做相关性排序和截断
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "napcat.memory", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MemoryTool {

    @Autowired(required = false)
    private MemoryStore memoryStore;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final java.time.ZoneId UTC = java.time.ZoneId.of("UTC");

    @Tool(
        name = "retrieve_memory",
        description = "检索用户的历史记忆摘要。当用户提到过去的事情、询问自己的偏好或历史记录时调用。\n" +
                "策略：优先返回每日归纳的摘要内容（精炼概括），摘要不足时才 fallback 到原始全量记录。\n" +
                "如果用户问的是'聊过什么''说过什么'等泛化问题，优先返回摘要，不要一次性列出所有原始对话。\n" +
                "只有用户追问'具体说了什么''第X条详细内容'时，才可能触发原始记录查询。\n" +
                "时间范围推断（如昨天、上周、5月10号）请填入 dateStart/dateEnd（格式 yyyy-MM-dd）；" +
                "query 参数填用户问题的核心语义，用于排序相关性高的记忆优先展示。"
    )
    public String retrieveMemory(
        @ToolParam(value = "query", description = "用户问题的核心语义，如'我聊过什么''我说过游戏吗''我的偏好是什么'", required = true) String query,
        @ToolParam(value = "dateStart", description = "时间范围起始日期，格式 yyyy-MM-dd。由Agent从用户语义中推断", required = false) String dateStart,
        @ToolParam(value = "dateEnd", description = "时间范围结束日期，格式 yyyy-MM-dd。默认为dateStart", required = false) String dateEnd,
        @ToolParam(value = "limit", description = "最多返回几条记忆，默认 5 条", required = false) Integer limit
    ) {
        SessionKey key = ToolRegistry.getCurrentSessionKey();
        if (key == null) {
            return "Error: No active session";
        }
        if (memoryStore == null) {
            return "Error: Memory store not available";
        }
        int max = limit != null && limit > 0 ? limit : 5;
        String q = query != null ? query.trim() : "";

        // 有日期范围：优先只返回该日期范围内的归纳摘要
        if (dateStart != null && !dateStart.isBlank()) {
            LocalDate start = parseDate(dateStart);
            LocalDate end = (dateEnd != null && !dateEnd.isBlank()) ? parseDate(dateEnd) : start;
            if (start == null) {
                return "Error: Invalid dateStart format. Expected yyyy-MM-dd.";
            }
            if (end == null) {
                end = start;
            }
            if (end.isBefore(start)) {
                LocalDate tmp = start; start = end; end = tmp;
            }

            log.debug("Memory retrieve by date range: {} ~ {}, query='{}'", start, end, q);

            // 第一步：只取归纳库该范围的摘要
            List<CandidateMemory> candidates = memoryStore.retrieveCandidatesByDateRange(key, start, end);
            List<CandidateMemory> summaries = candidates.stream()
                    .filter(c -> "summary".equals(c.getType()))
                    .toList();

            if (!summaries.isEmpty()) {
                List<String> matched = sortAndFormatCandidates(summaries, q, max);
                String header = String.format("【%s 至 %s 的记忆摘要】\n", start.format(DATE_FMT), end.format(DATE_FMT));
                return header + String.join("\n", matched);
            }

            // 第二步：归纳为空，fallback 到全量原始记录
            List<CandidateMemory> fullRecords = candidates.stream()
                    .filter(c -> !"summary".equals(c.getType()))
                    .toList();
            if (!fullRecords.isEmpty()) {
                List<String> matched = sortAndFormatCandidates(fullRecords, q, max);
                String header = String.format("【%s 至 %s 期间暂无归纳摘要，以下为原始记录】\n", start.format(DATE_FMT), end.format(DATE_FMT));
                return header + String.join("\n", matched);
            }

            // 第三步：该范围完全无记录，fallback 到最近全量
            List<String> fallback = memoryStore.retrieve(key, null, max);
            if (fallback.isEmpty()) {
                return String.format("%s 到 %s 期间没有找到记忆记录，且无任何历史记忆。", start.format(DATE_FMT), end.format(DATE_FMT));
            }
            String header = String.format("【%s 至 %s 期间未找到记录，以下是您的近期相关记忆】\n", start.format(DATE_FMT), end.format(DATE_FMT));
            return header + String.join("\n", fallback);
        }

        // 无日期范围：优先查归纳库最近摘要（30天内），归纳为空才 fallback 全量
        List<String> summaries = memoryStore.retrieveRecentSummaries(key, 30, max);
        if (!summaries.isEmpty()) {
            // 用 query 做简单相关性排序（摘要内容里含关键词的排前面）
            List<String> sorted = sortByRelevance(summaries, q, max);
            return "【近期记忆摘要】\n" + String.join("\n", sorted);
        }

        // fallback：归纳库为空，返回全量最近记忆
        List<String> memories = memoryStore.retrieve(key, null, max);
        if (memories.isEmpty()) {
            return "未找到历史记忆记录。";
        }
        return "【暂无归纳摘要，以下为近期原始记忆】\n" + String.join("\n", memories);
    }

    @Tool(
        name = "get_today_memory_summary",
        description = "获取用户今天的记忆摘要。当用户询问今天聊过什么时调用。"
    )
    public String getTodayMemorySummary(
        @ToolParam(value = "limit", description = "最多返回几条，默认 3 条", required = false) Integer limit
    ) {
        SessionKey key = ToolRegistry.getCurrentSessionKey();
        if (key == null) {
            return "Error: No active session";
        }
        if (memoryStore == null) {
            return "Error: Memory store not available";
        }
        int max = limit != null && limit > 0 ? limit : 3;
        String today = LocalDate.now(UTC).format(DATE_FMT);
        List<String> memories = memoryStore.retrieveByDate(key, today, max);
        if (memories.isEmpty()) {
            return "今天还没有记录。";
        }
        return String.join("\n", memories);
    }

    // ================================================================
    // 日期解析
    // ================================================================

    private LocalDate parseDate(String dateStr) {
        try {
            return LocalDate.parse(dateStr, DATE_FMT);
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================
    // 候选记忆语义匹配
    // ================================================================

    /**
     * 对候选记忆按 query 相关性排序，取前 limit 条返回。
     * 不硬性过滤零分候选，确保 LLM 能拿到完整上下文做语义分析。
     */
    private List<String> sortAndFormatCandidates(List<CandidateMemory> candidates, String query, int limit) {
        if (query == null || query.isBlank()) {
            return formatCandidates(candidates, limit);
        }

        String[] keywords = query.split("[\\s,，.。!！?？；;、]+");
        List<ScoredCandidate> scored = new ArrayList<>();
        for (CandidateMemory c : candidates) {
            String content = c.getContent();
            int score = 0;
            for (String kw : keywords) {
                if (kw.length() <= 1) continue;
                if (isStopWord(kw)) continue;
                if (content.contains(kw)) score++;
            }
            scored.add(new ScoredCandidate(c, score));
        }

        // 按分数降序，同分按日期降序（新的在前）
        scored.sort((a, b) -> {
            int cmp = Integer.compare(b.score, a.score);
            if (cmp != 0) return cmp;
            return b.candidate.getDate().compareTo(a.candidate.getDate());
        });

        List<String> results = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, scored.size()); i++) {
            CandidateMemory c = scored.get(i).candidate;
            String prefix = "summary".equals(c.getType()) ? "📋 " : "💬 ";
            String dateTag = "【" + c.getDate() + "】";
            results.add(prefix + dateTag + " " + c.getContent());
        }
        return results;
    }

    private List<String> formatCandidates(List<CandidateMemory> candidates, int limit) {
        List<String> results = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, candidates.size()); i++) {
            CandidateMemory c = candidates.get(i);
            String prefix = "summary".equals(c.getType()) ? "📋 " : "💬 ";
            String dateTag = "【" + c.getDate() + "】";
            results.add(prefix + dateTag + " " + c.getContent());
        }
        return results;
    }

    private static class ScoredCandidate {
        final CandidateMemory candidate;
        final int score;
        ScoredCandidate(CandidateMemory candidate, int score) {
            this.candidate = candidate;
            this.score = score;
        }
    }

    /**
     * 对已经格式化的字符串列表按 query 关键词做相关性排序。
     * 列表元素格式如：📋 【2026-05-01】 用户今天讨论了游戏...
     */
    private List<String> sortByRelevance(List<String> items, String query, int limit) {
        if (query == null || query.isBlank()) {
            return items.subList(0, Math.min(limit, items.size()));
        }

        String[] keywords = query.split("[\\s,，.。!！?？；;、]+");
        List<ScoredString> scored = new ArrayList<>();
        for (String item : items) {
            int score = 0;
            for (String kw : keywords) {
                if (kw.length() <= 1) continue;
                if (isStopWord(kw)) continue;
                if (item.contains(kw)) score++;
            }
            scored.add(new ScoredString(item, score));
        }

        scored.sort((a, b) -> Integer.compare(b.score, a.score));

        List<String> results = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, scored.size()); i++) {
            results.add(scored.get(i).text);
        }
        return results;
    }

    private static class ScoredString {
        final String text;
        final int score;
        ScoredString(String text, int score) {
            this.text = text;
            this.score = score;
        }
    }

    private boolean isStopWord(String word) {
        return switch (word) {
            case "我", "你", "他", "她", "它", "的", "了", "在", "是", "吗", "呢", "吧",
                 "什么", "怎么", "为什么", "多少", "几", "个", "条", "件", "次" -> true;
            default -> false;
        };
    }
}
