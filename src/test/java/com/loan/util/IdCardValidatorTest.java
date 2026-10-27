package com.loan.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 身份证工具测试 —— 重点是 {@link IdCardValidator#deriveBirthDate(String)}
 *
 * <p><b>为什么加这个方法</b>：建信用档案时出生日期是评分必需的
 * （{@code FeatureTransformationServiceImpl#calculateAge} 缺了会抛异常），
 * 但用户经常漏填。而身份证是必填的，生日必然能推出来 ——
 * 不该让用户为同一个信息填两遍，更不该因为漏填就静默不评分。
 */
class IdCardValidatorTest {

    @Test
    @DisplayName("从合法身份证推导出生日期")
    void derivesBirthDate() {
        assertThat(IdCardValidator.deriveBirthDate("110101199001011234"))
                .isEqualTo(LocalDate.of(1990, 1, 1));
        assertThat(IdCardValidator.deriveBirthDate("310101198805055553"))
                .isEqualTo(LocalDate.of(1988, 5, 5));
        assertThat(IdCardValidator.deriveBirthDate("440301199212127771"))
                .isEqualTo(LocalDate.of(1992, 12, 12));
    }

    @Test
    @DisplayName("身份证不合法（长度/字符/校验位格式）返回 null")
    void returnsNullForInvalidIdCard() {
        assertThat(IdCardValidator.deriveBirthDate(null)).isNull();
        assertThat(IdCardValidator.deriveBirthDate("")).isNull();
        assertThat(IdCardValidator.deriveBirthDate("123456")).isNull();
        assertThat(IdCardValidator.deriveBirthDate("11010119900101123A")).isNull();
    }

    @Test
    @DisplayName("日期部分非法（不存在的日期 / 年份越界）返回 null，不抛异常")
    void returnsNullForIllegalDate() {
        // 1990-02-30 不存在
        assertThat(IdCardValidator.deriveBirthDate("110101199002301234")).isNull();
        // 月份 13
        assertThat(IdCardValidator.deriveBirthDate("110101199013011234")).isNull();
        // 1800 年（早于 1900）
        assertThat(IdCardValidator.deriveBirthDate("110101180001011234")).isNull();
    }

    @Test
    @DisplayName("未来日期返回 null")
    void returnsNullForFutureDate() {
        String future = "110101" + LocalDate.now().plusYears(1).toString().replace("-", "") + "1234";
        assertThat(IdCardValidator.deriveBirthDate(future)).isNull();
    }
}
