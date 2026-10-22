package com.loan.service.impl;

import com.loan.entity.vo.loanDetail.ScorecardDetailVO;

import java.math.BigDecimal;

/**
 * 评分卡详情的展示装配器（阶段四 4.2 第 3 步）
 *
 * <p><b>为什么抽它</b>：{@code LoanApplicationServiceImpl} 有 1451 行，其中约 200 行是
 * "把内部数据翻译成前端可读文本"的纯函数 —— 等级划分、格式化描述、脱敏。
 * 这类代码：
 * <ul>
 *   <li><b>零业务依赖</b>（不查库、不调外部服务、不碰事务）→ 抽取风险最低</li>
 *   <li><b>被大量重复</b>（{@code ScorecardServiceImpl} 里还有一份几乎一样的副本）</li>
 *   <li><b>测试成本低</b>（纯输入输出，无需 mock）</li>
 * </ul>
 *
 * <p><b>设计约束</b>：全部为 {@code static} 方法，无状态、无注入。
 * 这样调用方不需要改依赖注入，直接静态调用即可。
 *
 * <p><b>为什么不合并 {@code ScorecardServiceImpl} 里的副本</b>：
 * 那是一个独立实现（部分阈值/文案不同），本步只做"搬移"、不做"行为变更"，
 * 避免一次改动引入两处回归。合并另开一步做（见阶段四文档 §4.2.5）。
 */
public final class ScorecardDetailAssembler {

    private ScorecardDetailAssembler() {
    }

    // ==================== 分数与决策 ====================

    /** 评分卡基准分（与训练时的 base score 一致，用于把 WOE 系数换算成百分制分数） */
    public static Integer calculateBaseScore() {
        return 791;
    }

    public static String getDecisionDesc(String decision) {
        if (decision == null) {
            return "未知";
        }
        switch (decision) {
            case "AUTO_APPROVE":
                return "自动通过";
            case "MANUAL_REVIEW":
                return "人工审核";
            case "REJECT":
                return "拒绝";
            default:
                return "未知";
        }
    }

    public static String getScoreLevel(Integer score) {
        if (score == null) {
            return "未知";
        }
        if (score >= 700) {
            return "优秀";
        } else if (score >= 650) {
            return "良好";
        } else if (score >= 600) {
            return "一般";
        } else {
            return "较差";
        }
    }

    // ==================== 特征逐项：等级 + 描述 ====================

    public static String getRevolvingUtilLevel(BigDecimal value) {
        if (value == null) return "未知";
        if (value.compareTo(new BigDecimal("0.2")) < 0) return "低";
        if (value.compareTo(new BigDecimal("0.5")) < 0) return "中";
        return "高";
    }

    public static String getRevolvingUtilDesc(BigDecimal value) {
        if (value == null) return "数据缺失";
        return String.format("循环信用利用率为 %.2f%%", value.doubleValue() * 100);
    }

    public static String getAgeLevel(Integer age) {
        if (age == null) return "未知";
        if (age < 35) return "年轻";
        if (age < 50) return "中年";
        return "年长";
    }

    public static String getAgeDesc(Integer age) {
        if (age == null) return "数据缺失";
        return String.format("年龄 %d 岁", age);
    }

    public static String getDebtRatioLevel(BigDecimal ratio) {
        if (ratio == null) return "未知";
        if (ratio.compareTo(new BigDecimal("0.3")) < 0) return "低";
        if (ratio.compareTo(new BigDecimal("0.6")) < 0) return "中";
        return "高";
    }

    public static String getDebtRatioDesc(BigDecimal ratio) {
        if (ratio == null) return "数据缺失";
        return String.format("负债率为 %.2f%%", ratio.doubleValue() * 100);
    }

    public static String getIncomeLevel(BigDecimal income) {
        if (income == null) return "未知";
        if (income.compareTo(new BigDecimal("5000")) < 0) return "低";
        if (income.compareTo(new BigDecimal("10000")) < 0) return "中";
        return "高";
    }

    public static String getIncomeDesc(BigDecimal income) {
        if (income == null) return "数据缺失";
        return String.format("月收入 ¥%.2f", income.doubleValue());
    }

    public static String getCreditLinesLevel(Integer lines) {
        if (lines == null) return "未知";
        if (lines < 5) return "少";
        if (lines < 10) return "中";
        return "多";
    }

    public static String getCreditLinesDesc(Integer lines) {
        if (lines == null) return "数据缺失";
        return String.format("信贷账户数 %d 个", lines);
    }

    public static String getDependentsLevel(Integer dependents) {
        if (dependents == null) return "未知";
        if (dependents <= 1) return "少";
        if (dependents <= 3) return "中";
        return "多";
    }

    public static String getDependentsDesc(Integer dependents) {
        if (dependents == null) return "数据缺失";
        return String.format("需赡养家属 %d 人", dependents);
    }

    // ==================== 类型转换（JSON 反序列化后常见 Object） ====================

    /** 把 JSON 反序列化出的各种数值类型统一成 BigDecimal；无法转换返回 null */
    public static BigDecimal toBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        if (o instanceof Number) {
            // 用字符串构造，避免 double 精度丢失
            return new BigDecimal(o.toString());
        }
        if (o instanceof String) {
            try {
                return new BigDecimal((String) o);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    /** 把 JSON 反序列化出的各种数值类型统一成 Integer；无法转换返回 null */
    public static Integer toInteger(Object o) {
        if (o == null) return null;
        if (o instanceof Integer) return (Integer) o;
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String) {
            try {
                return Integer.valueOf((String) o);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    /** 组装单个特征的展示对象 */
    public static ScorecardDetailVO.FeatureScore buildFeatureScore(
            String name, String desc, Object value, String level, String description) {
        ScorecardDetailVO.FeatureScore score = new ScorecardDetailVO.FeatureScore();
        score.setFeatureName(name);
        score.setFeatureDesc(desc);
        if (value instanceof BigDecimal) {
            score.setFeatureValue((BigDecimal) value);
        } else if (value instanceof Integer) {
            score.setFeatureValue(new BigDecimal(value.toString()));
        }
        score.setLevel(level);
        score.setDescription(description);
        return score;
    }

    // ==================== 脱敏 ====================

    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    public static String maskIdCard(String idCard) {
        if (idCard == null || idCard.length() < 10) {
            return idCard;
        }
        return idCard.substring(0, 6) + "********" + idCard.substring(idCard.length() - 4);
    }

    public static String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 8) {
            return cardNumber;
        }
        return cardNumber.substring(0, 4) + " **** **** " + cardNumber.substring(cardNumber.length() - 4);
    }

    // ==================== 状态描述 ====================

    public static String getKycStatusDesc(Integer kycStatus) {
        if (kycStatus == null) {
            return "未认证";
        }
        switch (kycStatus) {
            case 0:
                return "未认证";
            case 1:
                return "已认证";
            default:
                return "未知";
        }
    }

    public static String getVerifyStatusDesc(Integer verifyStatus) {
        if (verifyStatus == null) {
            return "未验证";
        }
        switch (verifyStatus) {
            case 0:
                return "未验证";
            case 1:
                return "已验证";
            default:
                return "未知";
        }
    }

    // ==================== 领域对象转换（阶段四 4.2 第 5 步补） ====================

    /**
     * 从信用档案实体构建评分卡入参。
     *
     * <p><b>为什么给默认值</b>：评分卡对每个特征都有固定 WOE 分箱，缺值时无法落箱。
     * 用"业务上最保守的中性值"填充（年龄 18 = 最低可申请年龄、利用率和负债率 0 = 无负担），
     * 可以让模型仍然输出分数，而不是直接崩掉或返回 null。
     *
     * <p><b>注意</b>：这只是"让流程能走下去"的兜底，不代表数据真实。
     * 真正缺数据的情况由 {@code RuleEvaluationService#missingRequiredMultiSources}
     * 显式暴露并转人工（避免静默放行）。
     */
    public static com.loan.entity.dto.UserProfileDTO buildUserProfileFromProfile(
            com.loan.entity.UserCreditProfile profile) {
        com.loan.entity.dto.UserProfileDTO userProfile = new com.loan.entity.dto.UserProfileDTO();
        userProfile.setRevolvingUtil(profile.getRevolvingUtil() != null ? profile.getRevolvingUtil() : BigDecimal.ZERO);
        userProfile.setAge(profile.getAge() != null ? profile.getAge() : 18);
        userProfile.setDebtRatio(profile.getDebtRatio() != null ? profile.getDebtRatio() : BigDecimal.ZERO);
        userProfile.setMonthlyIncome(profile.getMonthlyIncome());
        userProfile.setCreditLines(profile.getCreditLines() != null ? profile.getCreditLines() : 0);
        userProfile.setDependents(profile.getDependents() != null ? profile.getDependents() : 0);
        return userProfile;
    }

    /**
     * 枚举 → 数据库存的决策码。
     *
     * <p>为什么要转换：{@code ScorecardResultEnum} 用的是 {@code AUTO_PASS}，
     * 而 {@code loan_application.scorecard_decision} 存的是 {@code AUTO_APPROVE}。
     * <b>两套词汇不一致是历史包袱</b>，这里集中一处转换，避免散落各处。
     */
    public static String mapScorecardResultToDecisionCode(
            com.loan.entity.enums.ScorecardResultEnum result) {
        if (result == null) {
            return "UNKNOWN";
        }
        switch (result) {
            case AUTO_PASS:
                return "AUTO_APPROVE";
            case MANUAL_REVIEW:
                return "MANUAL_REVIEW";
            case AUTO_REJECT:
                return "REJECT";
            default:
                return "UNKNOWN";
        }
    }

    /** 特征转换明细（存进 risk_decision_log 供审计/回溯评分过程） */
    public static java.util.Map<String, Object> buildTransformationDetails(
            com.loan.entity.dto.UserProfileDTO profile) {
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("age", profile.getAge());
        details.put("dependents", profile.getDependents());
        details.put("monthlyIncome", profile.getMonthlyIncome());
        details.put("debtRatio", profile.getDebtRatio());
        details.put("revolvingUtil", profile.getRevolvingUtil());
        details.put("creditLines", profile.getCreditLines());
        return details;
    }
}
