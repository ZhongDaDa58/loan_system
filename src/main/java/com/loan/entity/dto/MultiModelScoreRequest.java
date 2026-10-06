package com.loan.entity.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.Map;

/**
 * 多模型评分请求（W4 · 主线 A）
 *
 * 与现网 `/scorecard/calculate` 的入参不同：本接口直接接收**特征键值**，
 * 因此可以传入多源特征（credit_query_cnt_3m、operator_pay_stability 等），
 * 由指定版本的模型按自身特征集取分。
 */
@Data
public class MultiModelScoreRequest {

    /** 特征键值（键名与模型配置 features 的键一致；可含传统特征与多源特征） */
    @NotEmpty(message = "特征数据不能为空")
    private Map<String, Object> features;

    /** 指定模型版本（如 v3 / v4 / v4-fusion）；为空则用默认版本 */
    private String modelVersion;

    /** 申请 ID（可选，用于决策留痕与影子对照） */
    private String applicationId;

    /** 用户 ID（可选，用于按用户维度追踪） */
    private String userId;
}
