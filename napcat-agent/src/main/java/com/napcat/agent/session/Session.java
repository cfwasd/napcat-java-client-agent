package com.napcat.agent.session;

import com.napcat.agent.llm.ChatMessage;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class Session {
    private final SessionKey key;
    private final List<ChatMessage> history = new ArrayList<>();
    private final long createdAt = System.currentTimeMillis();
    private volatile long lastAccessedAt = System.currentTimeMillis();

    /** 最大历史消息条数，0 或负数表示不限制 */
    private int maxHistory = 0;

    /** @deprecated 使用 {@link #Session(SessionKey, int)} 代替 */
    @Deprecated
    public Session(long userId) {
        this(new SessionKey(userId, SessionKey.PRIVATE), 0);
    }

    /** @deprecated 使用 {@link #Session(SessionKey, int)} 代替 */
    @Deprecated
    public Session(long userId, int maxHistory) {
        this(new SessionKey(userId, SessionKey.PRIVATE), maxHistory);
    }

    public Session(SessionKey key) {
        this(key, 0);
    }

    public Session(SessionKey key, int maxHistory) {
        this.key = key;
        this.maxHistory = maxHistory;
    }

    /** 便捷获取 userId */
    public long getUserId() {
        return key.userId();
    }

    /** 便捷获取 groupId（私聊时为 0） */
    public long getGroupId() {
        return key.groupId();
    }

    /** 返回历史消息快照（副本），避免外部遍历时的并发修改异常 */
    public synchronized List<ChatMessage> getHistory() {
        return new ArrayList<>(history);
    }

    public synchronized void addMessage(ChatMessage message) {
        history.add(message);
        lastAccessedAt = System.currentTimeMillis();
        truncateHistory();
    }

    private void truncateHistory() {
        if (maxHistory <= 0 || history.size() <= maxHistory) return;

        // 保留 system prompt（如有） + 最近 maxHistory 条
        int systemIdx = -1;
        for (int i = 0; i < history.size(); i++) {
            if ("system".equals(history.get(i).getRole())) {
                systemIdx = i;
                break;
            }
        }

        List<ChatMessage> truncated = new ArrayList<>();
        if (systemIdx >= 0) {
            truncated.add(history.get(systemIdx));
        }

        int keepRecent = systemIdx >= 0 ? maxHistory - 1 : maxHistory;
        int start = Math.max(0, history.size() - keepRecent);
        for (int i = start; i < history.size(); i++) {
            if (i != systemIdx) {
                truncated.add(history.get(i));
            }
        }

        history.clear();
        history.addAll(truncated);
    }

    public boolean isExpired(long ttlSeconds) {
        return (System.currentTimeMillis() - lastAccessedAt) > ttlSeconds * 1000;
    }

    public synchronized void clear() {
        history.clear();
        lastAccessedAt = System.currentTimeMillis();
    }

    /** 原子获取格式化的历史记录文本，用于持久化前读取 */
    public synchronized String getFormattedHistory() {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : history) {
            if ("user".equals(msg.getRole()) || "assistant".equals(msg.getRole())) {
                sb.append("[").append(msg.getRole()).append("]: ")
                  .append(msg.getContent() != null ? msg.getContent() : "").append("\n");
            }
        }
        return sb.toString().trim();
    }
}
