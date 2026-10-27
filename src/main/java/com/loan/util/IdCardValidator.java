package com.loan.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

public class IdCardValidator {

    /**
     * ⚠️ 必须用 STRICT 严格模式：默认的 SMART 模式会把 "19900230"（2 月 30 日）
     * 宽容地调整成 "1990-02-28"，于是非法身份证也能推出一个"生日"。
     * 另外 STRICT 下必须用 {@code uuuu}（year）而不是 {@code yyyy}（year-of-era）。
     */
    private static final DateTimeFormatter BIRTH_FMT =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    /**
     * 简单的身份证合法性校验（18位）
     */
    public static boolean isValid(String idCard) {
        if (idCard == null || idCard.length() != 18) return false;

        // 1. 前17位必须是数字
        for (int i = 0; i < 17; i++) {
            if (!Character.isDigit(idCard.charAt(i))) return false;
        }

        // 2. 校验位检查 (最后一位可以是X)
        char lastChar = idCard.charAt(17);
        if (!Character.isDigit(lastChar) && lastChar != 'X' && lastChar != 'x') return false;

        // 这里可以进一步加入 GB 11643-1999 的加权因子校验算法
        return true;
    }

    /**
     * 从身份证号推导出生日期（第 7~14 位，格式 {@code yyyyMMdd}）。
     *
     * <p><b>为什么需要它</b>：建信用档案时必须要有出生日期才能算分
     * （{@code FeatureTransformationServiceImpl#calculateAge} 缺了会抛异常），
     * 但很多用户会漏填这个字段 —— 而身份证是必填的，生日必然能推出来，
     * 不应该让用户为同一个信息填两遍。
     *
     * @param idCard 18 位身份证号
     * @return 出生日期；身份证不合法或日期部分非法时返回 {@code null}
     */
    public static LocalDate deriveBirthDate(String idCard) {
        if (!isValid(idCard)) {
            return null;
        }
        String birthPart = idCard.substring(6, 14);
        try {
            LocalDate date = LocalDate.parse(birthPart, BIRTH_FMT);
            // 合理性检查：1900 年之前或未来日期都视为非法
            if (date.getYear() < 1900 || date.isAfter(LocalDate.now())) {
                return null;
            }
            return date;
        } catch (DateTimeParseException e) {
            // 比如 19950230 这种不存在的日期
            return null;
        }
    }
}
