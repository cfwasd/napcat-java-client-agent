package com.napcat.agent.session;

import com.napcat.agent.memory.MemoryStore;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class SessionManager {

    private final Map<SessionKey, Session> sessions = new ConcurrentHashMap<>();
    private final long ttlSeconds;
    private final int maxHistoryMessages;
    private MemoryStore memoryStore;

    public SessionManager() {
        this(3600, 0);
    }

    public SessionManager(long ttlSeconds) {
        this(ttlSeconds, 0);
    }

    public SessionManager(long ttlSeconds, int maxHistoryMessages) {
        this.ttlSeconds = ttlSeconds;
        this.maxHistoryMessages = maxHistoryMessages;
    }

    public void setMemoryStore(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    /**
     * 获取当前所有会话键。
     */
    public java.util.Set<SessionKey> getAllKeys() {
        return new java.util.HashSet<>(sessions.keySet());
    }

    /**
     * 获取已有会话，不存在时返回 null。
     */
    public Session getIfPresent(SessionKey key) {
        return sessions.get(key);
    }

    /**
     * 获取或创建会话。使用 computeIfAbsent 确保原子操作。
     */
    public Session get(SessionKey key) {
        return sessions.computeIfAbsent(key, k -> {
            return new Session(k, maxHistoryMessages);
        });
    }

    /**
     * @deprecated 使用 {@link #get(SessionKey)} 代替
     */
    @Deprecated
    public Session get(long userId) {
        return get(SessionKey.ofPrivate(userId));
    }

    /**
     * 清除指定会话。
     * @deprecated 使用 {@link #getAndRemove(SessionKey)} 以在清除前提取记忆
     */
    @Deprecated
    public void clear(SessionKey key) {
        sessions.remove(key);
    }

    /**
     * 获取会话并移除。返回被移除的 Session，供记忆提取后清除。
     * @return 被移除的会话，若不存在则返回 null
     */
    public Session getAndRemove(SessionKey key) {
        Session removed = sessions.remove(key);
        if (removed != null) {
            log.debug("Session removed for extraction: {}", key);
        }
        return removed;
    }

    /**
     * @deprecated 使用 {@link #clear(SessionKey)} 代替
     */
    @Deprecated
    public void clear(long userId) {
        clear(SessionKey.ofPrivate(userId));
    }

    /**
     * 清除所有过期会话。清除前若启用了记忆存储，则全量保存会话历史。
     */
    public void clearExpired() {
        if (memoryStore != null) {
            for (Map.Entry<SessionKey, Session> entry : sessions.entrySet()) {
                Session session = entry.getValue();
                if (session.isExpired(ttlSeconds) && !session.getHistory().isEmpty()) {
                    memoryStore.persistFullSession(entry.getKey(), session.getFormattedHistory());
                }
            }
        }
        sessions.entrySet().removeIf(e -> e.getValue().isExpired(ttlSeconds));
    }
}
