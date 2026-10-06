package com.loan.entity;

import lombok.Data;

import java.util.Date;

/**
 * 影子评分记录（W4 · 主线 A）
 *
 * 一笔真实评分请求，由主模型出分并生效；影子模型（v4-fusion / XGBoost）旁路打分
 * **只记录、不影响结果**，用于新旧模型对照（分歧率/分布对比）。
 *
 * 说明：Java 侧不引入 XGBoost native 依赖，因此影子分数由**离线批量回填**
 * （shadow_status: PENDING → DONE），线上仅落记录，保证不阻塞、不增依赖。
 */
@Data
public class ShadowScoreLog {

    private Long id;

    /** 申请 ID（可空） */
    private String applicationId;

    /** 用户 ID（可空） */
    private String userId;

    /** 主模型注册键（线上生效的模型） */
    private String primaryModelKey;

    /** 主模型分数 */
    private Integer primaryScore;

    /** 主模型决策 */
    private String primaryDecision;

    /** 请求特征快照（JSON） */
    private String featuresJson;

    /** 影子状态：PENDING / DONE / FAILED */
    private String shadowStatus;

    /** 影子模型标识 */
    private String shadowModelKey;

    /** 影子分数 */
    private Integer shadowScore;

    /** 影子决策 */
    private String shadowDecision;

    /** 备注 */
    private String shadowNote;

    private Date createdAt;

    private Date scoredAt;
}
