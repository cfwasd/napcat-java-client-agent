package com.napcat.agent.memory;

import com.napcat.agent.session.SessionKey;

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
     * 持久化一条记忆。
     * @param key 用户会话键
     * @param content 记忆内容
     * @param type 记忆类型：fact / preference / topic / summary
     */
    void persist(SessionKey key, String content, String type);

    /**
     * 持久化一条记忆（默认类型 summary）。
     */
    default void persist(SessionKey key, String content) {
        persist(key, content, "summary");
    }

    void summarize(SessionKey key, String summaryDate, String content);

    List<SessionKey> listAllKeys();

    /**
     * 清除指定用户的所有记忆。
     */
    void clear(SessionKey key);
}
