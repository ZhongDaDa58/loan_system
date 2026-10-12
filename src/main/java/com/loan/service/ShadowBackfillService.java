package com.loan.service;

import java.util.Map;

/**
 * 影子分数回填服务（W5-A4）
 *
 * 背景：Java 侧不引入 XGBoost native 依赖，原计划的"离线 Python 回填 v4-fusion"
 *      在本机环境不可行（Python 3.14 无法安装 xgboost，且 v4-fusion 依赖 xgb_multi_score 特征）。
 * 方案：改为 **Java 侧回填** —— 用已加载的 LR 模型（v3 / v4）对 PENDING 记录打分并回写，
 *      目标模型自动避开主模型（primary=v4 → 影子用 v3；primary=v3 → 影子用 v4），保证对照有意义。
 */
public interface ShadowBackfillService {

    /**
     * 批量回填影子分数
     *
     * @param limit 单批处理上限
     * @return 统计：requested / done / failed / pendingLeft
     */
    Map<String, Object> backfillPending(int limit);
}
