package com.napcat.core.scheduler;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 定时任务 SQLite 存储。
 * 所有任务（注解声明的 + Agent 动态创建的）统一在此 CRUD。
 */
@Slf4j
public class ScheduleStore {

    private final DbManager dbManager;

    public ScheduleStore(DbManager dbManager) {
        this.dbManager = dbManager;
    }

    /**
     * 建表（通过 MigrationManager 调用）。
     */
    public static String ddl() {
        return "CREATE TABLE IF NOT EXISTS schedules (" +
                "id TEXT PRIMARY KEY," +
                "name TEXT NOT NULL," +
                "cron TEXT NOT NULL," +
                "action TEXT NOT NULL DEFAULT 'send_message'," +
                "target_type TEXT NOT NULL DEFAULT 'group'," +
                "target_id INTEGER NOT NULL," +
                "reply_text TEXT," +
                "prompt TEXT," +
                "enabled INTEGER DEFAULT 1," +
                "is_recurring INTEGER DEFAULT 1," +
                "created_by INTEGER DEFAULT 0," +
                "created_at TEXT DEFAULT (datetime('now'))," +
                "updated_at TEXT DEFAULT (datetime('now'))" +
                ")";
    }

    /**
     * 插入任务。
     * @return 生成的 ID
     */
    public String insert(ScheduleEntry entry) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String sql = "INSERT INTO schedules (id, name, cron, action, target_type, target_id, reply_text, prompt, enabled, is_recurring, created_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, entry.getName());
            ps.setString(3, entry.getCron());
            ps.setString(4, entry.getAction());
            ps.setString(5, entry.getTargetType());
            ps.setLong(6, entry.getTargetId());
            ps.setString(7, entry.getReplyText());
            ps.setString(8, entry.getPrompt());
            ps.setInt(9, entry.isEnabled() ? 1 : 0);
            ps.setInt(10, entry.isRecurring() ? 1 : 0);
            ps.setLong(11, entry.getCreatedBy() != null ? entry.getCreatedBy() : 0);
            ps.executeUpdate();
            log.info("Schedule created: id={}, name={}, cron={}", id, entry.getName(), entry.getCron());
            return id;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert schedule", e);
        }
    }

    /**
     * 按名称去重插入（用于注解声明任务，避免重启重复注册）。
     * 如果同名任务已存在则跳过。
     * @return 任务 ID，如果已存在则返回 null
     */
    public String insertOrIgnoreByName(ScheduleEntry entry) {
        // 先查是否存在同名
        String existing = findIdByName(entry.getName());
        if (existing != null) {
            log.debug("Schedule '{}' already exists, skipping", entry.getName());
            return null;
        }
        return insert(entry);
    }

    /**
     * 根据任务名称查找任务 ID。
     * @param name 任务名称
     * @return 任务 ID，如果不存在则返回 null
     */
    public String findIdByName(String name) {
        String sql = "SELECT id FROM schedules WHERE name = ? LIMIT 1";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("id");
            }
        } catch (SQLException e) {
            log.error("Failed to query schedule by name: {}", name, e);
        }
        return null;
    }

    /**
     * 查询所有启用的任务。
     */
    public List<ScheduleEntry> listEnabled() {
        String sql = "SELECT * FROM schedules WHERE enabled = 1";
        return query(sql);
    }

    /**
     * 按 ID 查询。
     */
    public ScheduleEntry getById(String id) {
        String sql = "SELECT * FROM schedules WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        } catch (SQLException e) {
            log.error("Failed to get schedule: {}", id, e);
        }
        return null;
    }

    /**
     * 删除任务。
     */
    public boolean delete(String id) {
        String sql = "DELETE FROM schedules WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            int rows = ps.executeUpdate();
            if (rows > 0) log.info("Schedule deleted: {}", id);
            return rows > 0;
        } catch (SQLException e) {
            log.error("Failed to delete schedule: {}", id, e);
            return false;
        }
    }

    /**
     * 启用/禁用一个任务。
     */
    public boolean toggle(String id, boolean enabled) {
        String sql = "UPDATE schedules SET enabled = ?, updated_at = datetime('now') WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, enabled ? 1 : 0);
            ps.setString(2, id);
            int rows = ps.executeUpdate();
            if (rows > 0) log.info("Schedule {} {}", id, enabled ? "enabled" : "disabled");
            return rows > 0;
        } catch (SQLException e) {
            log.error("Failed to toggle schedule: {}", id, e);
            return false;
        }
    }

    /**
     * 单次任务执行后禁用（而非删除），保留记录供查询。
     * @return true 如果执行了禁用操作
     */
    public boolean disableOneShot(String id) {
        ScheduleEntry entry = getById(id);
        if (entry != null && !entry.isRecurring()) {
            return toggle(id, false);
        }
        return false;
    }

    @Deprecated
    public boolean deleteIfOneShot(String id) {
        return disableOneShot(id);
    }

    /**
     * 查询所有任务。
     */
    public List<ScheduleEntry> listAll() {
        return query("SELECT * FROM schedules ORDER BY created_at DESC");
    }

    private List<ScheduleEntry> query(String sql) {
        List<ScheduleEntry> list = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to query schedules", e);
        }
        return list;
    }

    private ScheduleEntry mapRow(ResultSet rs) throws SQLException {
        ScheduleEntry e = new ScheduleEntry();
        e.setId(rs.getString("id"));
        e.setName(rs.getString("name"));
        e.setCron(rs.getString("cron"));
        e.setAction(rs.getString("action"));
        e.setTargetType(rs.getString("target_type"));
        e.setTargetId(rs.getLong("target_id"));
        e.setReplyText(rs.getString("reply_text"));
        e.setPrompt(rs.getString("prompt"));
        e.setEnabled(rs.getInt("enabled") == 1);
        e.setRecurring(rs.getInt("is_recurring") != 0);
        e.setCreatedBy(rs.getLong("created_by"));
        e.setCreatedAt(rs.getString("created_at"));
        e.setUpdatedAt(rs.getString("updated_at"));
        return e;
    }

    @Data
    public static class ScheduleEntry {
        private String id;
        private String name;
        private String cron;
        private String action = "send_message";
        private String targetType = "group";
        private long targetId;
        private String replyText;
        private String prompt;
        private boolean enabled = true;
        private boolean recurring = true;
        private Long createdBy;
        private String createdAt;
        private String updatedAt;

        @Deprecated
        public boolean isOneShot() {
            return !recurring;
        }
    }
}