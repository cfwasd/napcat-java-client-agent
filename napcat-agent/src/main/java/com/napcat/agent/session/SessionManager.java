package com.napcat.agent.session;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class SessionManager {

    private final Map<SessionKey, Session> sessions = new ConcurrentHashMap<>();
    private final long ttlSeconds;
    private final int maxHistoryMessages;

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
     */
    public void clear(SessionKey key) {
        Session removed = sessions.remove(key);
        if (removed != null) {
            log.debug("Session cleared: {}", key);
        }
    }

    /**
     * @deprecated 使用 {@link #clear(SessionKey)} 代替
     */
    @Deprecated
    public void clear(long userId) {
        clear(SessionKey.ofPrivate(userId));
    }

    /**
     * 清除所有过期会话。
     */
    public void clearExpired() {
        sessions.entrySet().removeIf(e -> e.getValue().isExpired(ttlSeconds));
    }
}
