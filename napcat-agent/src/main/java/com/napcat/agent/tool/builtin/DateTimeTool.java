package com.napcat.agent.tool.builtin;

import com.napcat.core.annotation.Tool;
import com.napcat.core.annotation.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * 内置日期时间工具，提供当前时间、时区转换、日期计算等功能。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "napcat.agent.builtin.date-time", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DateTimeTool {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FMT_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 获取当前日期和时间。
     *
     * @param timezone 时区，如 Asia/Shanghai、America/New_York，默认为系统时区
     */
    @Tool(
        name = "get_current_time",
        description = "获取当前日期和时间。可指定时区。"
    )
    public String getCurrentTime(
        @ToolParam(value = "timezone", description = "IANA 时区 ID，如 Asia/Shanghai、America/New_York。不传则使用系统时区。", required = false)
        String timezone
    ) {
        ZonedDateTime now;
        try {
            ZoneId zone = (timezone != null && !timezone.isBlank())
                    ? ZoneId.of(timezone)
                    : ZoneId.systemDefault();
            now = ZonedDateTime.now(zone);
        } catch (Exception e) {
            return "时区无效：" + timezone + "。请使用 IANA 时区格式，如 Asia/Shanghai。";
        }

        String dayOfWeek = now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINESE);
        return "🕐 当前时间：" + now.format(FMT) + " " + dayOfWeek
                + "\n时区：" + now.getZone().getId()
                + "\nUnix 时间戳：" + now.toEpochSecond();
    }

    /**
     * 计算两个日期之间的天数差。
     */
    @Tool(
        name = "days_between",
        description = "计算两个日期之间相差多少天。"
    )
    public String daysBetween(
        @ToolParam(value = "from", description = "起始日期，格式 yyyy-MM-dd", required = true) String from,
        @ToolParam(value = "to", description = "结束日期，格式 yyyy-MM-dd", required = true) String to
    ) {
        try {
            LocalDate fromDate = LocalDate.parse(from, FMT_DATE);
            LocalDate toDate = LocalDate.parse(to, FMT_DATE);
            long days = ChronoUnit.DAYS.between(fromDate, toDate);
            return "📅 " + from + " 到 " + to + " 相差 " + Math.abs(days) + " 天"
                    + (days >= 0 ? "（含起始日）" : "（结束日早于起始日）");
        } catch (Exception e) {
            return "日期格式错误，请使用 yyyy-MM-dd 格式。";
        }
    }

    /**
     * 获取星期几。
     */
    @Tool(
        name = "get_day_of_week",
        description = "查询某个日期是星期几。"
    )
    public String getDayOfWeek(
        @ToolParam(value = "date", description = "日期，格式 yyyy-MM-dd。不传则使用今天。", required = false) String date
    ) {
        try {
            LocalDate d = (date != null && !date.isBlank())
                    ? LocalDate.parse(date, FMT_DATE)
                    : LocalDate.now();
            String dayOfWeek = d.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINESE);
            return "📅 " + d.format(FMT_DATE) + " 是 " + dayOfWeek;
        } catch (Exception e) {
            return "日期格式错误，请使用 yyyy-MM-dd 格式。";
        }
    }
}
