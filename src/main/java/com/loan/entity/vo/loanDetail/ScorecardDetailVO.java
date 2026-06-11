package com.loan.entity.vo.loanDetail;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class ScorecardDetailVO {

    // ===== 总评分 =====
    private Integer totalScore;           // 总评分
    private String scoreLevel;            // 评分等级（优秀/良好/一般/较差）
    private String decision;              // 决策结果（AUTO_APPROVE/MANUAL_REVIEW/REJECT）
    private String decisionDesc;          // 决策描述

    // ===== 基础分 =====
    private Integer baseScore;            // 基础分（由 intercept 计算）

    // ===== 六个核心维度分数 =====
    private FeatureScore revolvingUtil;   // 循环信用利用率
    private FeatureScore age;             // 年龄
    private FeatureScore debtRatio;       // 负债率
    private FeatureScore monthlyIncome;   // 月收入
    private FeatureScore creditLines;     // 信贷账户数
    private FeatureScore dependents;      // 家属数量

    @Data
    public static class FeatureScore {
        private String featureName;       // 特征名称
        private String featureDesc;       // 特征描述
        private BigDecimal featureValue;  // 特征值
        private Integer score;            // 该维度得分
        private String level;             // 等级（高/中/低）
        private String description;       // 说明
    }
}
