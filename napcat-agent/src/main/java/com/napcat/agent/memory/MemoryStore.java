package com.napcat.agent.memory;

import com.napcat.agent.session.SessionKey;

import java.time.LocalDate;
import java.util.List;

/**
 * 长期记忆存储接口。
 * 跨会话持久化用户摘要信息，在 Agent 对话开始时检索并注入上下文。
 */
public interface MemoryStore {

    /**
     * 检索与查询相关的记忆。
     * @param key 用户会话键
     * @param query 查询文本（用于语义匹配，简单实现用关键词）
     * @param limit 最大返回条数
     * @return 记忆文本列表（按相关性排序）
     */
    List<String> retrieve(SessionKey key, String query, int limit);

    /**
     * 按日期检索记忆。先查归纳库（memory_summaries），无结果再查全量库（memories）。
     * @param key 用户会话键
     * @param date 日期，格式 yyyy-MM-dd
     * @param limit 最大返回条数
     * @return 记忆文本列表
     */
    List<String> retrieveByDate(SessionKey key, String date, int limit);

    /**
     * 按日期范围检索候选记忆（归纳库 + 全量库合并）。
     * 返回该日期范围内的所有记忆，不限制条数，由调用方二次筛选。
     * @param key 用户会话键
     * @param startDate 起始日期（含）
     * @param endDate 结束日期（含）
     * @return 记忆文本列表
     */
    List<CandidateMemory> retrieveCandidatesByDateRange(SessionKey key, LocalDate startDate, LocalDate endDate);

    /**
     * 仅检索归纳库（memory_summaries），支持关键词过滤。
     * @param key 用户会话键
     * @param query 关键词过滤（null/blank 时不过滤）
     * @param limit 最大条数
     * @return 归纳摘要列表，按日期从新到旧
     */
    List<String> retrieveSummaries(SessionKey key, String query, int limit);

    /**
     * 检索最近 N 天的归纳摘要。
     * @param key 用户会话键
     * @param days 最近多少天
     * @param limit 最大条数
     * @return 归纳摘要列表，按日期从新到旧
     */
    List<String> retrieveRecentSummaries(SessionKey key, int days, int limit);

    /**
     * 持久化一条记忆。
     * @param key 用户会话键
     * @param content 记忆内容
     * @param type 记忆类型：fact / preference / topic / summary
     */
    void persist(SessionKey key, String content, String type);

    /**
     * 持久化一条记忆（带重要度）。
     * @param key 用户会话键
     * @param content 记忆内容
     * @param type 记忆类型
     * @param importance 重要度 1-5
     */
    default void persist(SessionKey key, String content, String type, int importance) {
        persist(key, content, type);
    }

    /**
     * 持久化一条记忆（默认类型 summary）。
     */
    default void persist(SessionKey key, String content) {
        persist(key, content, "summary");
    }

    /**
     * 全量持久化会话历史。
     * @param key 用户会话键
     * @param fullContent 完整会话内容文本
     */
    void persistFullSession(SessionKey key, String fullContent);

    void summarize(SessionKey key, String summaryDate, String content);

    List<SessionKey> listAllKeys();

    /**
     * 清除指定用户的所有记忆。
     */
    void clear(SessionKey key);

    /**
     * 候选记忆对象，用于日期范围检索后的二次匹配。
     */
    class CandidateMemory {
        private final String content;
        private final String type;
        private final String date;

        public CandidateMemory(String content, String type, String date) {
            this.content = content;
            this.type = type;
            this.date = date;
        }

        public String getContent() { return content; }
        public String getType() { return type; }
        public String getDate() { return date; }
    }
}
