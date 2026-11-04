package com.quanxiaoha.xiaohashu.count.biz.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** 根据事件发生时间生成稳定的每日计数去重分片。 */
public final class CountSeenBucketUtils {
    private static final DateTimeFormatter BUCKET_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    /** 工具类不允许实例化。 */
    private CountSeenBucketUtils() {
    }

    /** 将事件发生时间转换为 yyyyMMdd 分片。 */
    public static String from(LocalDateTime occurredAt) {
        if (occurredAt == null) {
            throw new IllegalArgumentException("计数事件发生时间不能为空");
        }
        return occurredAt.toLocalDate().format(BUCKET_FORMATTER);
    }

    /** 解析事件中保存的 ISO 时间并转换为 yyyyMMdd 分片。 */
    public static String from(String occurredAt) {
        if (occurredAt == null || occurredAt.isBlank()) {
            throw new IllegalArgumentException("计数事件发生时间不能为空");
        }
        try {
            return from(LocalDateTime.parse(occurredAt));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("计数事件发生时间格式错误", exception);
        }
    }

    /** 判断分片是否为真实存在的 yyyyMMdd 日期。 */
    public static boolean isValid(String seenBucket) {
        if (seenBucket == null || seenBucket.length() != 8) {
            return false;
        }
        try {
            LocalDate.parse(seenBucket, BUCKET_FORMATTER);
            return true;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }
}
