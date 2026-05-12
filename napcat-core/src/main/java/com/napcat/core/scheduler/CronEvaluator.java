package com.napcat.core.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Cron 表达式求值工具。
 * 基于 Spring CronExpression，计算下一次触发时间。
 */
@Slf4j
public class CronEvaluator {

    /** 轮询提前窗口：5 分钟（毫秒） */
    public static final long POLL_WINDOW_MS = 5 * 60 * 1000;

    /**
     * 判断任务的下次触发时间是否在未来 windowMs 毫秒内。
     * @param cron cron 表达式
     * @param from 起始时间
     * @param windowMs 时间窗口（毫秒）
     * @return 如果在窗口内则返回触发时间，否则返回 null
     */
    public static Instant nextTriggerInWindow(String cron, Instant from, long windowMs) {
        Instant next = nextTrigger(cron, from);
        if (next == null) return null;

        long diff = next.toEpochMilli() - from.toEpochMilli();
        if (diff >= 0 && diff <= windowMs) {
            log.debug("cron={} next trigger in {}ms (within {}ms window)", cron, diff, windowMs);
            return next;
        }
        return null;
    }

    /**
     * 计算 cron 表达式的下一次触发时间。
     * @param cron cron 表达式
     * @param from 起始时间
     * @return 下次触发时间，解析失败返回 null
     */
    public static Instant nextTrigger(String cron, Instant from) {
        try {
            CronExpression expr = CronExpression.parse(cron);
            LocalDateTime fromLdt = LocalDateTime.ofInstant(from, ZoneId.systemDefault());
            LocalDateTime next = expr.next(fromLdt);
            if (next == null) return null;
            return next.atZone(ZoneId.systemDefault()).toInstant();
        } catch (Exception e) {
            log.error("Invalid cron expression: {}", cron, e);
            return null;
        }
    }

    /**
     * 是否为有效 cron 表达式。
     */
    public static boolean isValid(String cron) {
        try {
            CronExpression.parse(cron);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断 cron 表达式是否为循环任务（在未来至少能触发两次）。
     */
    public static boolean isRecurring(String cron) {
        try {
            CronExpression expr = CronExpression.parse(cron);
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime next1 = expr.next(now);
            if (next1 == null) return false;
            LocalDateTime next2 = expr.next(next1);
            return next2 != null;
        } catch (Exception e) {
            return false;
        }
    }
}
