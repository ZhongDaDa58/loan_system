package com.loan.service.impl;

import com.loan.entity.dto.UserProfileDTO;
import com.loan.entity.enums.ScorecardResultEnum;
import com.loan.entity.vo.loanDetail.ScorecardDetailVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ScorecardDetailAssembler} 的单元测试（阶段四收尾）
 *
 * <p><b>为什么优先测这个类</b>：拆分后它的覆盖率只有 <b>9%</b>（116 行）——
 * 集成测试只走了申请提交主路径，完全没覆盖展示组装。
 * 而它是<b>纯函数集合</b>（零依赖、零 mock），测试成本极低、跑得极快。
 *
 * <p><b>测试重点</b>：
 * <ol>
 *   <li><b>边界值</b>：等级划分都是 {@code <} / {@code <=} 判断，
 *       阈值"刚好等于"时的行为必须锁住（如利用率 0.2 是"中"还是"低"）</li>
 *   <li><b>null 输入</b>：生产环境最常见 —— 特征缺失时必须返回"数据缺失"而不是抛 NPE</li>
 *   <li><b>短字符串脱敏</b>：长度不足时原样返回，不能越界截取</li>
 *   <li><b>类型转换</b>：Jackson 反序列化出的 Object 可能是 Integer/Long/Double/String，都要能转</li>
 * </ol>
 */
@DisplayName("ScorecardDetailAssembler 展示组装")
class ScorecardDetailAssemblerTest {

    // ==================== 分数与决策 ====================

    @Nested
    @DisplayName("评分等级 getScoreLevel")
    class ScoreLevel {

        @Test
        @DisplayName("null → 未知（不抛 NPE）")
        void nullScore() {
            assertThat(ScorecardDetailAssembler.getScoreLevel(null)).isEqualTo("未知");
        }

        @Test
        @DisplayName("边界：700 是优秀的下限（>= 700）")
        void boundaryAt700() {
            assertThat(ScorecardDetailAssembler.getScoreLevel(700)).isEqualTo("优秀");
            assertThat(ScorecardDetailAssembler.getScoreLevel(699)).isEqualTo("良好");
        }

        @Test
        @DisplayName("边界：650 是良好的下限")
        void boundaryAt650() {
            assertThat(ScorecardDetailAssembler.getScoreLevel(650)).isEqualTo("良好");
            assertThat(ScorecardDetailAssembler.getScoreLevel(649)).isEqualTo("一般");
        }

        @Test
        @DisplayName("边界：600 是一般的下限")
        void boundaryAt600() {
            assertThat(ScorecardDetailAssembler.getScoreLevel(600)).isEqualTo("一般");
            assertThat(ScorecardDetailAssembler.getScoreLevel(599)).isEqualTo("较差");
        }

        @Test
        @DisplayName("极端值")
        void extremes() {
            assertThat(ScorecardDetailAssembler.getScoreLevel(0)).isEqualTo("较差");
            assertThat(ScorecardDetailAssembler.getScoreLevel(1000)).isEqualTo("优秀");
        }
    }

    @Nested
    @DisplayName("决策描述 getDecisionDesc")
    class DecisionDesc {

        @Test
        @DisplayName("三种决策码 + null + 未知码")
        void allCases() {
            assertThat(ScorecardDetailAssembler.getDecisionDesc("AUTO_APPROVE")).isEqualTo("自动通过");
            assertThat(ScorecardDetailAssembler.getDecisionDesc("MANUAL_REVIEW")).isEqualTo("人工审核");
            assertThat(ScorecardDetailAssembler.getDecisionDesc("REJECT")).isEqualTo("拒绝");
            assertThat(ScorecardDetailAssembler.getDecisionDesc(null)).isEqualTo("未知");
            assertThat(ScorecardDetailAssembler.getDecisionDesc("SOMETHING_ELSE")).isEqualTo("未知");
        }
    }

    @Test
    @DisplayName("基准分固定 791（与训练时 base score 一致）")
    void baseScore() {
        assertThat(ScorecardDetailAssembler.calculateBaseScore()).isEqualTo(791);
    }

    // ==================== 特征等级：边界值 ====================

    @Nested
    @DisplayName("循环信用利用率")
    class RevolvingUtil {

        @Test
        @DisplayName("边界：0.2 归入「中」（判断是 < 0.2）")
        void boundary() {
            assertThat(ScorecardDetailAssembler.getRevolvingUtilLevel(new BigDecimal("0.19"))).isEqualTo("低");
            assertThat(ScorecardDetailAssembler.getRevolvingUtilLevel(new BigDecimal("0.2"))).isEqualTo("中");
            assertThat(ScorecardDetailAssembler.getRevolvingUtilLevel(new BigDecimal("0.49"))).isEqualTo("中");
            assertThat(ScorecardDetailAssembler.getRevolvingUtilLevel(new BigDecimal("0.5"))).isEqualTo("高");
        }

        @Test
        @DisplayName("null → 未知；描述 → 数据缺失")
        void nullHandling() {
            assertThat(ScorecardDetailAssembler.getRevolvingUtilLevel(null)).isEqualTo("未知");
            assertThat(ScorecardDetailAssembler.getRevolvingUtilDesc(null)).isEqualTo("数据缺失");
        }

        @Test
        @DisplayName("描述格式化：0.85 → 85.00%")
        void descFormat() {
            assertThat(ScorecardDetailAssembler.getRevolvingUtilDesc(new BigDecimal("0.85")))
                    .isEqualTo("循环信用利用率为 85.00%");
        }
    }

    @Nested
    @DisplayName("年龄")
    class Age {

        @Test
        @DisplayName("边界：35 是「中年」下限，50 是「年长」下限")
        void boundary() {
            assertThat(ScorecardDetailAssembler.getAgeLevel(34)).isEqualTo("年轻");
            assertThat(ScorecardDetailAssembler.getAgeLevel(35)).isEqualTo("中年");
            assertThat(ScorecardDetailAssembler.getAgeLevel(49)).isEqualTo("中年");
            assertThat(ScorecardDetailAssembler.getAgeLevel(50)).isEqualTo("年长");
        }

        @Test
        @DisplayName("null 处理")
        void nullHandling() {
            assertThat(ScorecardDetailAssembler.getAgeLevel(null)).isEqualTo("未知");
            assertThat(ScorecardDetailAssembler.getAgeDesc(null)).isEqualTo("数据缺失");
        }

        @Test
        @DisplayName("描述格式化")
        void descFormat() {
            assertThat(ScorecardDetailAssembler.getAgeDesc(42)).isEqualTo("年龄 42 岁");
        }
    }

    @Nested
    @DisplayName("负债率")
    class DebtRatio {

        @Test
        @DisplayName("边界：0.3 / 0.6")
        void boundary() {
            assertThat(ScorecardDetailAssembler.getDebtRatioLevel(new BigDecimal("0.29"))).isEqualTo("低");
            assertThat(ScorecardDetailAssembler.getDebtRatioLevel(new BigDecimal("0.3"))).isEqualTo("中");
            assertThat(ScorecardDetailAssembler.getDebtRatioLevel(new BigDecimal("0.59"))).isEqualTo("中");
            assertThat(ScorecardDetailAssembler.getDebtRatioLevel(new BigDecimal("0.6"))).isEqualTo("高");
        }

        @Test
        @DisplayName("null 处理 + 描述格式化")
        void nullAndFormat() {
            assertThat(ScorecardDetailAssembler.getDebtRatioLevel(null)).isEqualTo("未知");
            assertThat(ScorecardDetailAssembler.getDebtRatioDesc(null)).isEqualTo("数据缺失");
            assertThat(ScorecardDetailAssembler.getDebtRatioDesc(new BigDecimal("0.35")))
                    .isEqualTo("负债率为 35.00%");
        }
    }

    @Nested
    @DisplayName("月收入")
    class Income {

        @Test
        @DisplayName("边界：5000 / 10000")
        void boundary() {
            assertThat(ScorecardDetailAssembler.getIncomeLevel(new BigDecimal("4999"))).isEqualTo("低");
            assertThat(ScorecardDetailAssembler.getIncomeLevel(new BigDecimal("5000"))).isEqualTo("中");
            assertThat(ScorecardDetailAssembler.getIncomeLevel(new BigDecimal("9999"))).isEqualTo("中");
            assertThat(ScorecardDetailAssembler.getIncomeLevel(new BigDecimal("10000"))).isEqualTo("高");
        }

        @Test
        @DisplayName("null 处理 + 描述格式化（带 ¥ 符号）")
        void nullAndFormat() {
            assertThat(ScorecardDetailAssembler.getIncomeLevel(null)).isEqualTo("未知");
            assertThat(ScorecardDetailAssembler.getIncomeDesc(null)).isEqualTo("数据缺失");
            assertThat(ScorecardDetailAssembler.getIncomeDesc(new BigDecimal("8000")))
                    .isEqualTo("月收入 ¥8000.00");
        }
    }

    @Nested
    @DisplayName("信贷账户数")
    class CreditLines {

        @Test
        @DisplayName("边界：5 / 10")
        void boundary() {
            assertThat(ScorecardDetailAssembler.getCreditLinesLevel(4)).isEqualTo("少");
            assertThat(ScorecardDetailAssembler.getCreditLinesLevel(5)).isEqualTo("中");
            assertThat(ScorecardDetailAssembler.getCreditLinesLevel(9)).isEqualTo("中");
            assertThat(ScorecardDetailAssembler.getCreditLinesLevel(10)).isEqualTo("多");
        }

        @Test
        @DisplayName("null 处理 + 描述格式化")
        void nullAndFormat() {
            assertThat(ScorecardDetailAssembler.getCreditLinesLevel(null)).isEqualTo("未知");
            assertThat(ScorecardDetailAssembler.getCreditLinesDesc(null)).isEqualTo("数据缺失");
            assertThat(ScorecardDetailAssembler.getCreditLinesDesc(7)).isEqualTo("信贷账户数 7 个");
        }
    }

    @Nested
    @DisplayName("需赡养家属")
    class Dependents {

        @Test
        @DisplayName("边界：<=1 是「少」, <=3 是「中」（注意是 <=，与其它等级不同）")
        void boundary() {
            assertThat(ScorecardDetailAssembler.getDependentsLevel(0)).isEqualTo("少");
            assertThat(ScorecardDetailAssembler.getDependentsLevel(1)).isEqualTo("少");
            assertThat(ScorecardDetailAssembler.getDependentsLevel(2)).isEqualTo("中");
            assertThat(ScorecardDetailAssembler.getDependentsLevel(3)).isEqualTo("中");
            assertThat(ScorecardDetailAssembler.getDependentsLevel(4)).isEqualTo("多");
        }

        @Test
        @DisplayName("null 处理 + 描述格式化")
        void nullAndFormat() {
            assertThat(ScorecardDetailAssembler.getDependentsLevel(null)).isEqualTo("未知");
            assertThat(ScorecardDetailAssembler.getDependentsDesc(null)).isEqualTo("数据缺失");
            assertThat(ScorecardDetailAssembler.getDependentsDesc(3)).isEqualTo("需赡养家属 3 人");
        }
    }

    // ==================== 类型转换 ====================

    @Nested
    @DisplayName("toBigDecimal —— Jackson 反序列化后的 Object 统一化")
    class ToBigDecimal {

        @Test
        @DisplayName("null → null")
        void nullInput() {
            assertThat(ScorecardDetailAssembler.toBigDecimal(null)).isNull();
        }

        @Test
        @DisplayName("BigDecimal 原样返回")
        void bigDecimalPassthrough() {
            BigDecimal v = new BigDecimal("12.34");
            assertThat(ScorecardDetailAssembler.toBigDecimal(v)).isSameAs(v);
        }

        @Test
        @DisplayName("Integer / Long / Double 都能转")
        void numbers() {
            assertThat(ScorecardDetailAssembler.toBigDecimal(18)).isEqualByComparingTo("18");
            assertThat(ScorecardDetailAssembler.toBigDecimal(18L)).isEqualByComparingTo("18");
            assertThat(ScorecardDetailAssembler.toBigDecimal(0.85d)).isEqualByComparingTo("0.85");
        }

        @Test
        @DisplayName("字符串能转；非法字符串 → null（不抛异常）")
        void strings() {
            assertThat(ScorecardDetailAssembler.toBigDecimal("0.5")).isEqualByComparingTo("0.5");
            assertThat(ScorecardDetailAssembler.toBigDecimal("not-a-number")).isNull();
            assertThat(ScorecardDetailAssembler.toBigDecimal("")).isNull();
        }

        @Test
        @DisplayName("不支持的类型（如 Boolean）→ null")
        void unsupportedType() {
            assertThat(ScorecardDetailAssembler.toBigDecimal(Boolean.TRUE)).isNull();
        }
    }

    @Nested
    @DisplayName("toInteger")
    class ToInteger {

        @Test
        @DisplayName("null → null")
        void nullInput() {
            assertThat(ScorecardDetailAssembler.toInteger(null)).isNull();
        }

        @Test
        @DisplayName("Integer 原样；其它 Number 取 intValue（带截断）")
        void numbers() {
            assertThat(ScorecardDetailAssembler.toInteger(42)).isEqualTo(42);
            assertThat(ScorecardDetailAssembler.toInteger(42L)).isEqualTo(42);
            assertThat(ScorecardDetailAssembler.toInteger(42.9d)).isEqualTo(42);  // 截断，不是四舍五入
        }

        @Test
        @DisplayName("字符串能转；非法 → null")
        void strings() {
            assertThat(ScorecardDetailAssembler.toInteger("7")).isEqualTo(7);
            assertThat(ScorecardDetailAssembler.toInteger("abc")).isNull();
        }

        @Test
        @DisplayName("不支持的类型 → null")
        void unsupportedType() {
            assertThat(ScorecardDetailAssembler.toInteger(Boolean.FALSE)).isNull();
        }
    }

    // ==================== 脱敏 ====================

    @Nested
    @DisplayName("脱敏 mask*")
    class Masking {

        @Test
        @DisplayName("手机号：11 位 → 前3 + **** + 后4")
        void phone() {
            assertThat(ScorecardDetailAssembler.maskPhone("13900009001")).isEqualTo("139****9001");
        }

        @Test
        @DisplayName("手机号：null 或不足 7 位 → 原样返回（不越界截取）")
        void phoneShort() {
            assertThat(ScorecardDetailAssembler.maskPhone(null)).isNull();
            assertThat(ScorecardDetailAssembler.maskPhone("123456")).isEqualTo("123456");
            assertThat(ScorecardDetailAssembler.maskPhone("1234567")).isEqualTo("123****4567");
        }

        @Test
        @DisplayName("身份证：18 位 → 前6 + ******** + 后4")
        void idCard() {
            assertThat(ScorecardDetailAssembler.maskIdCard("350102199001011234"))
                    .isEqualTo("350102********1234");
        }

        @Test
        @DisplayName("身份证：null 或不足 10 位 → 原样返回")
        void idCardShort() {
            assertThat(ScorecardDetailAssembler.maskIdCard(null)).isNull();
            assertThat(ScorecardDetailAssembler.maskIdCard("123456789")).isEqualTo("123456789");
        }

        @Test
        @DisplayName("银行卡：前4 + **** **** + 后4")
        void cardNumber() {
            assertThat(ScorecardDetailAssembler.maskCardNumber("6222021234567890123"))
                    .isEqualTo("6222 **** **** 0123");
        }

        @Test
        @DisplayName("银行卡：null 或不足 8 位 → 原样返回")
        void cardNumberShort() {
            assertThat(ScorecardDetailAssembler.maskCardNumber(null)).isNull();
            assertThat(ScorecardDetailAssembler.maskCardNumber("1234567")).isEqualTo("1234567");
            assertThat(ScorecardDetailAssembler.maskCardNumber("12345678")).isEqualTo("1234 **** **** 5678");
        }
    }

    // ==================== 状态描述 ====================

    @Nested
    @DisplayName("认证/验证状态描述")
    class StatusDesc {

        @Test
        @DisplayName("KYC：0 未认证、1 已认证、null 未认证、其它 未知")
        void kyc() {
            assertThat(ScorecardDetailAssembler.getKycStatusDesc(0)).isEqualTo("未认证");
            assertThat(ScorecardDetailAssembler.getKycStatusDesc(1)).isEqualTo("已认证");
            assertThat(ScorecardDetailAssembler.getKycStatusDesc(null)).isEqualTo("未认证");
            assertThat(ScorecardDetailAssembler.getKycStatusDesc(9)).isEqualTo("未知");
        }

        @Test
        @DisplayName("实名验证：0 未验证、1 已验证、null 未验证、其它 未知")
        void verify() {
            assertThat(ScorecardDetailAssembler.getVerifyStatusDesc(0)).isEqualTo("未验证");
            assertThat(ScorecardDetailAssembler.getVerifyStatusDesc(1)).isEqualTo("已验证");
            assertThat(ScorecardDetailAssembler.getVerifyStatusDesc(null)).isEqualTo("未验证");
            assertThat(ScorecardDetailAssembler.getVerifyStatusDesc(9)).isEqualTo("未知");
        }
    }

    // ==================== 枚举映射 ====================

    @Nested
    @DisplayName("枚举 → 数据库决策码（两套词汇不一致是历史包袱）")
    class DecisionCodeMapping {

        @Test
        @DisplayName("AUTO_PASS → AUTO_APPROVE（注意不是同名）")
        void autoPass() {
            assertThat(ScorecardDetailAssembler.mapScorecardResultToDecisionCode(ScorecardResultEnum.AUTO_PASS))
                    .isEqualTo("AUTO_APPROVE");
        }

        @Test
        @DisplayName("MANUAL_REVIEW 同名")
        void manualReview() {
            assertThat(ScorecardDetailAssembler.mapScorecardResultToDecisionCode(ScorecardResultEnum.MANUAL_REVIEW))
                    .isEqualTo("MANUAL_REVIEW");
        }

        @Test
        @DisplayName("AUTO_REJECT → REJECT（注意不是同名）")
        void autoReject() {
            assertThat(ScorecardDetailAssembler.mapScorecardResultToDecisionCode(ScorecardResultEnum.AUTO_REJECT))
                    .isEqualTo("REJECT");
        }

        @Test
        @DisplayName("null → UNKNOWN（不抛 NPE）")
        void nullInput() {
            assertThat(ScorecardDetailAssembler.mapScorecardResultToDecisionCode(null)).isEqualTo("UNKNOWN");
        }
    }

    // ==================== 组装 ====================

    @Nested
    @DisplayName("buildFeatureScore 组装单个特征")
    class BuildFeatureScore {

        @Test
        @DisplayName("BigDecimal 值直接设置")
        void bigDecimalValue() {
            ScorecardDetailVO.FeatureScore fs = ScorecardDetailAssembler.buildFeatureScore(
                    "revolvingUtil", "循环信用利用率", new BigDecimal("0.35"), "中", "描述");
            assertThat(fs.getFeatureName()).isEqualTo("revolvingUtil");
            assertThat(fs.getFeatureDesc()).isEqualTo("循环信用利用率");
            assertThat(fs.getFeatureValue()).isEqualByComparingTo("0.35");
            assertThat(fs.getLevel()).isEqualTo("中");
            assertThat(fs.getDescription()).isEqualTo("描述");
        }

        @Test
        @DisplayName("Integer 值转成 BigDecimal")
        void integerValue() {
            ScorecardDetailVO.FeatureScore fs = ScorecardDetailAssembler.buildFeatureScore(
                    "age", "年龄", 35, "中年", "年龄 35 岁");
            assertThat(fs.getFeatureValue()).isEqualByComparingTo("35");
        }

        @Test
        @DisplayName("null 值 → featureValue 保持 null（不抛异常）")
        void nullValue() {
            ScorecardDetailVO.FeatureScore fs = ScorecardDetailAssembler.buildFeatureScore(
                    "age", "年龄", null, "未知", "数据缺失");
            assertThat(fs.getFeatureValue()).isNull();
            assertThat(fs.getLevel()).isEqualTo("未知");
        }

        @Test
        @DisplayName("字符串值 → featureValue 保持 null（只认 BigDecimal / Integer）")
        void stringValueIgnored() {
            ScorecardDetailVO.FeatureScore fs = ScorecardDetailAssembler.buildFeatureScore(
                    "x", "x", "not-a-number", "-", "-");
            assertThat(fs.getFeatureValue()).isNull();
        }
    }

    @Nested
    @DisplayName("buildTransformationDetails 特征明细")
    class BuildTransformationDetails {

        @Test
        @DisplayName("6 个特征都进 Map，key 是驼峰命名")
        void allFields() {
            UserProfileDTO p = new UserProfileDTO();
            p.setAge(35);
            p.setDependents(2);
            p.setMonthlyIncome(new BigDecimal("8000"));
            p.setDebtRatio(new BigDecimal("0.4"));
            p.setRevolvingUtil(new BigDecimal("0.3"));
            p.setCreditLines(6);

            Map<String, Object> details = ScorecardDetailAssembler.buildTransformationDetails(p);

            assertThat(details).hasSize(6)
                    .containsEntry("age", 35)
                    .containsEntry("dependents", 2)
                    .containsEntry("monthlyIncome", new BigDecimal("8000"))
                    .containsEntry("debtRatio", new BigDecimal("0.4"))
                    .containsEntry("revolvingUtil", new BigDecimal("0.3"))
                    .containsEntry("creditLines", 6);
        }

        @Test
        @DisplayName("null 特征也进 Map（key 存在、值为 null）")
        void nullFields() {
            UserProfileDTO p = new UserProfileDTO();
            Map<String, Object> details = ScorecardDetailAssembler.buildTransformationDetails(p);
            assertThat(details).hasSize(6).containsKey("monthlyIncome");
            assertThat(details.get("monthlyIncome")).isNull();
        }
    }
}
