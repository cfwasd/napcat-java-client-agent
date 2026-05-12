package com.napcat.agent.scheduler;

import com.napcat.agent.session.SessionKey;
import com.napcat.agent.tool.ToolRegistry;
import com.napcat.core.annotation.Tool;
import com.napcat.core.annotation.ToolParam;
import com.napcat.core.scheduler.CronEvaluator;
import com.napcat.core.scheduler.SchedulePoller;
import com.napcat.core.scheduler.ScheduleStore;
import com.napcat.core.scheduler.ScheduleStore.ScheduleEntry;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent 可调用的定时任务管理工具。
 * Cron 格式：秒 分 时 日 月 周
 * 示例：0 0 8 * * ? = 每天8:00，0 30 14 * * ? = 每天14:30，0 0 9 * * 1-5 = 工作日9:00
 */
@Slf4j
public class ScheduleTool {

    private final ScheduleStore store;
    private final SchedulePoller poller;

    public ScheduleTool() {
        this.store = null;
        this.poller = null;
    }

    public ScheduleTool(ScheduleStore store, SchedulePoller poller) {
        this.store = store;
        this.poller = poller;
    }

    @Tool(name = "create_schedule",
          description = "创建一个定时任务。cron 用 6 位格式「秒 分 时 日 月 周」，如「0 0 8 * * ?」表示每天早上8点。" +
                        "action 填 ai_generate（AI 生成内容）或 send_message（固定文本）。" +
                        "ai_generate 时必填 prompt，send_message 时必填 replyText。")
    public String createSchedule(
            @ToolParam(value = "cron", description = "Cron表达式，6位：秒 分 时 日 月 周。如 0 0 8 * * ?", required = true)
            String cron,
            @ToolParam(value = "name", description = "任务名称", required = true)
            String name,
            @ToolParam(value = "targetId", description = "目标群号或用户QQ，不填默认当前对话目标")
            Long targetId,
            @ToolParam(value = "action", description = "动作类型：ai_generate(默认) 或 send_message",
                       enums = {"ai_generate", "send_message"})
            String action,
            @ToolParam(value = "targetType", description = "目标类型：group 或 private",
                       enums = {"group", "private"})
            String targetType,
            @ToolParam(value = "replyText", description = "固定回复文本（仅 send_message 时有效）")
            String replyText,
            @ToolParam(value = "prompt", description = "AI 生成提示词（仅 ai_generate 时有效）")
            String prompt
    ) {
        if (!CronEvaluator.isValid(cron)) {
            return "❌ Cron 表达式无效：" + cron + "。请使用 6 位标准格式，如「0 0 8 * * ?」（每天早上8点）。";
        }

        if (name != null && name.matches("^\\d+\\s+.*")) {
            log.warn("Task name looks like cron expression: '{}', rejecting", name);
            return "❌ 错误：任务名称不能是 Cron 表达式！请提供简短的任务描述，如'喝水提醒'、'早安问候'。";
        }

        // 验证参数完整性
        String validatedAction = (action != null && !action.isBlank()) ? action : "ai_generate";

        if ("send_message".equals(validatedAction) && (replyText == null || replyText.isBlank())) {
            return "❌ send_message 模式下 replyText 必填。示例：replyText=\"记得喝水哦~\"";
        }
        if ("ai_generate".equals(validatedAction) && (prompt == null || prompt.isBlank())) {
            return "❌ ai_generate 模式下 prompt 必填。示例：prompt=\"提醒大家喝水休息\"";
        }

        SessionKey sessionKey = ToolRegistry.getCurrentSessionKey();
        
        long autoTargetId = 0;
        String autoTargetType = "group";
        Long createdBy = null;
        
        if (sessionKey != null) {
            if (sessionKey.isPrivate()) {
                autoTargetId = sessionKey.userId();
                autoTargetType = "private";
                createdBy = sessionKey.userId();
            } else if (sessionKey.isGroup()) {
                autoTargetId = sessionKey.groupId();
                autoTargetType = "group";
                createdBy = sessionKey.userId();
            }
        }

        long finalTargetId = (targetId != null && targetId > 0) ? targetId : autoTargetId;
        String finalTargetType = (targetType != null && !targetType.isBlank()) ? targetType : autoTargetType;

        if (finalTargetId <= 0) {
            return "❌ 无法确定目标。请指定 targetId 或在群聊/私聊中使用。";
        }

        String existingId = store.findIdByName(name);
        if (existingId != null) {
            if (poller != null) poller.cancelTask(existingId);
            store.delete(existingId);
            log.info("Replaced existing schedule: name={}, oldId={}", name, existingId);
        }

        ScheduleEntry entry = new ScheduleEntry();
        entry.setName(name);
        entry.setCron(cron);
        entry.setTargetId(finalTargetId);
        entry.setAction(validatedAction);
        entry.setTargetType(finalTargetType);
        entry.setReplyText(replyText);
        entry.setPrompt(prompt);
        entry.setEnabled(true);
        entry.setCreatedBy(createdBy);
        entry.setRecurring(CronEvaluator.isRecurring(cron));

        String id = store.insert(entry);

        if (poller != null) {
            entry.setId(id);
            poller.scheduleNow(entry);
        }

        String actionDesc = "ai_generate".equals(validatedAction) ? "AI生成" : "固定文本";
        String typeInfo = entry.isRecurring() ? "循环" : "单次";
        String replaceInfo = existingId != null ? "（已替换同名任务）" : "";
        return "✅ 定时任务已创建" + replaceInfo + "：\n" +
                "- ID：" + id + "\n" +
                "- 名称：" + name + "\n" +
                "- Cron：" + cron + "\n" +
                "- 动作：" + actionDesc + "\n" +
                "- 类型：" + typeInfo + "\n" +
                "- 目标：" + entry.getTargetType() + "/" + finalTargetId;
    }

    @Tool(name = "delete_schedule", description = "删除一个定时任务。")
    public String deleteSchedule(
            @ToolParam(value = "idOrName", description = "任务ID或名称", required = true)
            String idOrName
    ) {
        ScheduleEntry entry = store.getById(idOrName);
        if (entry == null) {
            List<ScheduleEntry> all = store.listAll();
            entry = all.stream()
                    .filter(e -> e.getName() != null && e.getName().contains(idOrName))
                    .findFirst().orElse(null);
        }
        if (entry == null) return "❌ 未找到任务：" + idOrName;
        if (poller != null) poller.cancelTask(entry.getId());
        store.delete(entry.getId());
        return "✅ 已删除：" + entry.getName();
    }

    @Tool(name = "list_schedules", description = "列出所有已创建的定时任务。")
    public String listSchedules() {
        List<ScheduleEntry> all = store.listAll();
        if (all.isEmpty()) return "📋 当前没有定时任务。";
        String list = all.stream()
                .map(e -> "- **" + e.getName() + "** [" + (e.isEnabled() ? "启用" : "禁用") + "]\n"
                        + "  ID：" + e.getId() + " | Cron：" + e.getCron() + " | " + e.getAction())
                .collect(Collectors.joining("\n"));
        return "📋 定时任务（共 " + all.size() + " 个）：\n" + list;
    }

    @Tool(name = "toggle_schedule", description = "启用或禁用一个定时任务。")
    public String toggleSchedule(
            @ToolParam(value = "idOrName", description = "任务ID或名称", required = true)
            String idOrName,
            @ToolParam(value = "enabled", description = "true=启用，false=禁用", required = true)
            boolean enabled
    ) {
        ScheduleEntry entry = store.getById(idOrName);
        if (entry == null) {
            List<ScheduleEntry> all = store.listAll();
            entry = all.stream()
                    .filter(e -> e.getName() != null && e.getName().contains(idOrName))
                    .findFirst().orElse(null);
        }
        if (entry == null) return "❌ 未找到任务：" + idOrName;
        store.toggle(entry.getId(), enabled);
        if (enabled && poller != null) poller.scheduleNow(entry);
        else if (poller != null) poller.cancelTask(entry.getId());
        return "✅ " + entry.getName() + " 已" + (enabled ? "启用" : "禁用");
    }
}
