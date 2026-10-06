package com.loan.service;

import com.loan.entity.dto.MultiModelScoreRequest;
import com.loan.entity.vo.MultiModelScoreResultVO;

import java.util.List;
import java.util.Map;

/**
 * 多模型评分服务（W4 · 主线 A3）
 *
 * 与既有 {@code ScorecardService}（现网单模型、固定 6 特征入参）并存：
 * 本服务按**特征键值 + 模型版本**评分，支持多源特征与版本路由/降级。
 */
public interface MultiModelScorecardService {

    /** 按指定版本（或默认版本）评分；请求版本不可用时自动降级 */
    MultiModelScoreResultVO calculate(MultiModelScoreRequest request);

    /** 注册表概览（供前端/评审展示可用模型） */
    List<Map<String, Object>> listModels();

    /** 单个模型详情（不存在返回 null） */
    Map<String, Object> getModel(String modelKey);
}
