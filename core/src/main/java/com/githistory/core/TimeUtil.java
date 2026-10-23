package com.githistory.core;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 时间格式化工具
 */
public final class TimeUtil {

    private static final DateTimeFormatter ABSOLUTE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private TimeUtil() {
    }

    /** 绝对时间 */
    public static String absolute(long epochMillis) {
        return ABSOLUTE.format(Instant.ofEpochMilli(epochMillis));
    }

    /**
     * 相对时间, 如 "3 分钟前" / "2 天前" / "1 年前"
     */
    public static String relative(long epochMillis) {
        long diff = System.currentTimeMillis() - epochMillis;
        if (diff < 0) {
            return absolute(epochMillis);
        }
        Duration d = Duration.ofMillis(diff);
        long minutes = d.toMinutes();
        if (minutes < 1) {
            return "刚刚";
        }
        if (minutes < 60) {
            return minutes + " 分钟前";
        }
        long hours = d.toHours();
        if (hours < 24) {
            return hours + " 小时前";
        }
        long days = d.toDays();
        if (days < 30) {
            return days + " 天前";
        }
        long months = days / 30;
        if (months < 12) {
            return months + " 个月前";
        }
        long years = days / 365;
        return years + " 年前";
    }
}
