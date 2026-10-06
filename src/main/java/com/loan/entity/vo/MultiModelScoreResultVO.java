package com.loan.entity.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 多模型评分结果（W4 · 主线 A）
 *
 * 与既有 {@link ScorecardResultVO} 并存，额外携带"模型版本 / 是否降级 / 缺失特征"等
 * 多模型化信息，供版本切换与降级观测使用。
 */
@Data
@Builder
public class MultiModelScoreResultVO {

    /** 实际使用的模型注册键（如 v3 / v4 / v4-fusion） */
    private String modelKey;

    /** 模型版本（配置内声明） */
    private String modelVersion;

    /** 模型类型（lr / lr+xgb） */
    private String modelType;

    /** 请求的版本（可能为空） */
    private String requestedVersion;

    /** 是否发生了自动降级（实际模型 != 请求模型） */
    private boolean degraded;

    /** 该模型要求的特征总数 */
    private Integer requiredFeatureCount;

    /** 本次未提供的特征（如未同步多源数据时） */
    private List<String> missingFeatures;

    /** 总分（300-950） */
    private Integer totalScore;

    /** 分数等级 */
    private String scoreLevel;

    /** 决策：AUTO_APPROVE / MANUAL_REVIEW / REJECT */
    private String decision;

    /** 决策描述 */
    private String decisionDesc;

    /** 各特征分箱得分明细 */
    private Map<String, Object> featureScores;

    /** 评分时间 */
    private Date scoredAt;
}
