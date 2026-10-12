package com.loan.service;

import com.loan.entity.ProductThresholdSuggestion;

import java.util.List;
import java.util.Map;

/**
 * 阈值治理服务（P0 · 标定 → 配置 打通）
 *
 * 职责：
 *  1) 导入离线标定产出（建议阈值）；
 *  2) 查询"当前阈值 vs 建议阈值"对比；
 *  3) 采纳建议 → 写入产品配置 + 记录变更历史（source=CALIBRATED）。
 */
public interface ThresholdGovernanceService {

    /**
     * 批量导入标定建议
     *
     * @return 成功导入条数
     */
    int importSuggestions(List<ProductThresholdSuggestion> suggestions);

    /**
     * 查询某产品的标定建议（含当前阈值对比）
     *
     * @return null 表示产品不存在
     */
    Map<String, Object> getSuggestion(String productId);

    /**
     * 采纳最新标定建议
     *
     * @return null 表示产品不存在或无可用建议
     */
    Map<String, Object> adoptSuggestion(String productId, String reason);
}
