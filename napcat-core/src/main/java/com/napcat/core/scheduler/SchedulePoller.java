package com.napcat.core.scheduler;

import com.napcat.core.scheduler.ScheduleStore.ScheduleEntry;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 定时任务轮询器。
 * 每 5 分钟扫描 SQLite 中的 enabled 任务，将未来 5 分钟内触发的任务注册到 TimerWheel。
 * 启动时全量扫描恢复所有未过期任务。
 */
@Slf4j
public class SchedulePoller {

    private final ScheduleStore store;
    private final TimerWheel timerWheel;
    private final Consumer<ScheduleEntry> fireCallback;
    private final long pollIntervalMs;
    private final long pollWindowMs;

    private ScheduledExecutorService pollExecutor;

    public SchedulePoller(ScheduleStore store, TimerWheel timerWheel, Consumer<ScheduleEntry> fireCallback) {
        this(store, timerWheel, fireCallback, 5 * 60 * 1000, CronEvaluator.POLL_WINDOW_MS);
    }

    public SchedulePoller(ScheduleStore store, TimerWheel timerWheel, Consumer<ScheduleEntry> fireCallback,
                          long pollIntervalMs, long pollWindowMs) {
        this.store = store;
        this.timerWheel = timerWheel;
        this.fireCallback = fireCallback;
        this.pollIntervalMs = pollIntervalMs;
        this.pollWindowMs = pollWindowMs;
    }

    /**
     * 启动轮询器。
     * 1. 全量扫描恢复所有未过期任务
     * 2. 启动定时轮询
     */
    public void start() {
        log.info("SchedulePoller starting: pollInterval={}ms, pollWindow={}ms", pollIntervalMs, pollWindowMs);
        pollExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "napcat-poller");
            t.setDaemon(true);
            return t;
        });

        // 启动时全量扫描
        rescheduleAll();

        // 定时轮询
        pollExecutor.scheduleWithFixedDelay(
                this::rescheduleAll,
                pollIntervalMs,
                pollIntervalMs,
                TimeUnit.MILLISECONDS
        );

        log.info("SchedulePoller started, {} active timers", timerWheel.activeCount());
    }

    /**
     * 停止轮询器。
     */
    public void stop() {
        if (pollExecutor != null && !pollExecutor.isShutdown()) {
            pollExecutor.shutdown();
            try {
                pollExecutor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        timerWheel.cancelAll();
        log.info("SchedulePoller stopped");
    }

    /**
     * 全量扫描所有启用任务，将未来窗口内的注册到 TimerWheel。
     */
    public void rescheduleAll() {
        try {
            List<ScheduleEntry> tasks = store.listEnabled();
            Instant now = Instant.now();
            int scheduled = 0;

            for (ScheduleEntry task : tasks) {
                Instant next = CronEvaluator.nextTriggerInWindow(task.getCron(), now, pollWindowMs);
                if (next != null) {
                    timerWheel.schedule(task.getId(), next, () -> fire(task));
                    scheduled++;
                }
            }

            log.debug("Polled {} enabled tasks, {} scheduled for next window", tasks.size(), scheduled);
        } catch (Exception e) {
            log.error("SchedulePoller poll error", e);
        }
    }

    /**
     * 立即注册单个任务（新增/修改任务时调用，无需等下次轮询）。
     */
    public void scheduleNow(ScheduleEntry entry) {
        Instant now = Instant.now();
        Instant next = CronEvaluator.nextTrigger(entry.getCron(), now);
        if (next != null) {
            timerWheel.schedule(entry.getId(), next, () -> fire(entry));
            log.info("Manually scheduled: id={}, cron={}", entry.getId(), entry.getCron());
        }
    }

    /**
     * 从 TimerWheel 中取消某个任务。
     */
    public void cancelTask(String taskId) {
        timerWheel.cancel(taskId);
    }

    /**
     * 触发回调，并在执行后处理重复任务的重注册。
     */
    private void fire(ScheduleEntry entry) {
        try {
            log.info("Firing schedule: id={}, name={}", entry.getId(), entry.getName());
            fireCallback.accept(entry);
        } catch (Exception e) {
            log.error("Schedule fire error: id={}", entry.getId(), e);
        }

        // 非循环任务执行后自动禁用
        if (!entry.isRecurring()) {
            store.toggle(entry.getId(), false);
            log.info("One-shot schedule disabled: {}", entry.getId());
            return;
        }

        // 重复任务：立即计算下一次触发并重新注册
        Instant next = CronEvaluator.nextTrigger(entry.getCron(), Instant.now());
        if (next != null) {
            timerWheel.schedule(entry.getId(), next, () -> fire(entry));
            log.debug("Recurring schedule re-scheduled: id={}, next={}", entry.getId(), next);
        }
    }
}