package com.napcat.agent.memory;

import com.napcat.agent.session.SessionKey;
import com.napcat.core.scheduler.DbManager;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * SQLite 实现的长记忆存储。
 * 按 (userId, groupId) 隔离，支持关键词匹配检索。
 * 
 * 两层结构：
 * - memories 表：即时提取的碎片化记忆（fact / preference / topic）
 * - memory_summaries 表：每日凌晨归纳的摘要（优先检索）
 */
@Slf4j
public class SqliteMemoryStore implements MemoryStore {

    private final DbManager dbManager;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public SqliteMemoryStore(DbManager dbManager) {
        this.dbManager = dbManager;
    }

    /**
     * memories 表 DDL（通过 MigrationManager 执行）。
     */
    public static String memoriesDdl() {
        return "CREATE TABLE IF NOT EXISTS memories (" +
                "id TEXT PRIMARY KEY," +
                "user_id INTEGER NOT NULL," +
                "group_id INTEGER NOT NULL DEFAULT 0," +
                "content TEXT NOT NULL," +
                "type TEXT DEFAULT 'summary'," +
                "importance INTEGER DEFAULT 1," +
                "created_at TEXT DEFAULT (datetime('now'))" +
                ")";
    }

    /**
     * memory_summaries 表 DDL — 每日归纳摘要。
     */
    public static String summariesDdl() {
        return "CREATE TABLE IF NOT EXISTS memory_summaries (" +
                "id TEXT PRIMARY KEY," +
                "user_id INTEGER NOT NULL," +
                "group_id INTEGER NOT NULL DEFAULT 0," +
                "summary_date TEXT NOT NULL," +
                "content TEXT NOT NULL," +
                "created_at TEXT DEFAULT (datetime('now'))" +
                ")";
    }

    // ================================================================
    // retrieve — 优先检索归纳表，其次明细表
    // ================================================================

    @Override
    public List<String> retrieve(SessionKey key, String query, int limit) {
        List<String> results = new ArrayList<>();
        int remaining = limit;

        // 第一步：检索 memory_summaries（归纳表优先）
        if (query != null && !query.isBlank()) {
            String sql = "SELECT content FROM memory_summaries WHERE user_id = ? AND group_id = ? AND content LIKE ? " +
                    "ORDER BY summary_date DESC LIMIT ?";
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, key.userId());
                ps.setLong(2, key.groupId());
                ps.setString(3, "%" + query + "%");
                ps.setInt(4, remaining);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String content = rs.getString("content");
                        if (content != null && !content.isBlank()) {
                            results.add("📋 " + content);
                        }
                    }
                }
            } catch (SQLException e) {
                log.error("Failed to retrieve summaries for {}", key, e);
            }
        }

        remaining = limit - results.size();
        if (remaining <= 0) return results;

        // 第二步：检索 memories（明细表兜底）
        String sql;
        if (query != null && !query.isBlank()) {
            sql = "SELECT content FROM memories WHERE user_id = ? AND group_id = ? AND content LIKE ? " +
                    "ORDER BY importance DESC, created_at DESC LIMIT ?";
        } else {
            sql = "SELECT content FROM memories WHERE user_id = ? AND group_id = ? " +
                    "ORDER BY importance DESC, created_at DESC LIMIT ?";
        }

        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, key.userId());
            ps.setLong(2, key.groupId());
            if (query != null && !query.isBlank()) {
                ps.setString(3, "%" + query + "%");
                ps.setInt(4, remaining);
            } else {
                ps.setInt(3, remaining);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(rs.getString("content"));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to retrieve memories for {}", key, e);
        }
        return results;
    }

    // ================================================================
    // persist
    // ================================================================

    @Override
    public void persist(SessionKey key, String content, String type) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String sql = "INSERT INTO memories (id, user_id, group_id, content, type) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setLong(2, key.userId());
            ps.setLong(3, key.groupId());
            ps.setString(4, content);
            ps.setString(5, type);
            ps.executeUpdate();
            log.debug("Memory persisted: userId={}, type={}", key.userId(), type);
        } catch (SQLException e) {
            log.error("Failed to persist memory for {}", key, e);
        }
    }

    // ================================================================
    // summarize — 每日归纳
    // ================================================================

    @Override
    public void summarize(SessionKey key, String summaryDate, String content) {
        if (content == null || content.isBlank()) return;
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String sql = "INSERT INTO memory_summaries (id, user_id, group_id, summary_date, content) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setLong(2, key.userId());
            ps.setLong(3, key.groupId());
            ps.setString(4, summaryDate);
            ps.setString(5, content);
            ps.executeUpdate();
            log.info("Memory summary saved: key={}, date={}", key, summaryDate);
        } catch (SQLException e) {
            log.error("Failed to save summary for {} on {}", key, summaryDate, e);
        }
    }

    // ================================================================
    // listAllKeys — 列出所有有记忆记录的用户键
    // ================================================================

    @Override
    public List<SessionKey> listAllKeys() {
        Set<SessionKey> keys = new HashSet<>();
        // 从 memories 表收集
        String sql = "SELECT DISTINCT user_id, group_id FROM memories";
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                keys.add(new SessionKey(rs.getLong("user_id"), rs.getLong("group_id")));
            }
        } catch (SQLException e) {
            log.error("Failed to list memory keys", e);
        }
        // 从 memory_summaries 表收集
        String sql2 = "SELECT DISTINCT user_id, group_id FROM memory_summaries";
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql2)) {
            while (rs.next()) {
                keys.add(new SessionKey(rs.getLong("user_id"), rs.getLong("group_id")));
            }
        } catch (SQLException e) {
            log.error("Failed to list summary keys", e);
        }
        return new ArrayList<>(keys);
    }

    /**
     * 获取指定用户键当天所有记忆内容（用于 LLM 归纳）。
     * @return 拼接后的文本，若当天无记忆则返回 null
     */
    public String getTodayMemories(SessionKey key) {
        String today = LocalDate.now().format(DATE_FMT);
        String sql = "SELECT type, content FROM memories WHERE user_id = ? AND group_id = ? AND date(created_at) = ? " +
                "ORDER BY importance DESC, created_at ASC";
        StringBuilder sb = new StringBuilder();
        int count = 0;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, key.userId());
            ps.setLong(2, key.groupId());
            ps.setString(3, today);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String type = rs.getString("type");
                    String content = rs.getString("content");
                    if (content != null && !content.isBlank()) {
                        sb.append("- [").append(type).append("] ").append(content).append("\n");
                        count++;
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get today memories for {}", key, e);
        }
        return count > 0 ? sb.toString().trim() : null;
    }

    // ================================================================
    // clear
    // ================================================================

    @Override
    public void clear(SessionKey key) {
        // 清除 memories
        String sql = "DELETE FROM memories WHERE user_id = ? AND group_id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, key.userId());
            ps.setLong(2, key.groupId());
            int rows = ps.executeUpdate();
            if (rows > 0) {
                log.info("Memories cleared for {}: {} records", key, rows);
            }
        } catch (SQLException e) {
            log.error("Failed to clear memories for {}", key, e);
        }
        // 同时清除 summaries
        String sql2 = "DELETE FROM memory_summaries WHERE user_id = ? AND group_id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql2)) {
            ps.setLong(1, key.userId());
            ps.setLong(2, key.groupId());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to clear summaries for {}", key, e);
        }
    }
}
