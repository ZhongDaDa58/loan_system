package com.loan.controller;

import com.loan.entity.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审核标签回流接口（S1）
 *
 * <p>设计依据：`多数据融合评分卡设计-最终版.md` §8 ——
 * 「人工审核结论（通过/拒绝原因、**欺诈标签**）必须回传至模型训练库，
 * 作为下一轮特征工程筛选与评分卡迭代的 **Y 标签**」。
 *
 * <p>本接口导出「**特征快照 + Y 标签**」样本；离线侧由
 * {@code scorecard_project/scripts/ingest_audit_labels.py} 读取并合并进训练集。
 *
 * <p>⚠️ 权限说明：当前沿用的是系统现有的登录鉴权（`LoginInterceptor` 只校验登录、
 * 不校验角色）。如需限制为管理员专用，需另行加固（见测试问题 P-022）。
 */
@RestController
@RequestMapping("/api/v1/audit/labels")
@Tag(name = "审核标签回流", description = "S1：导出「特征 + Y 标签」样本供模型训练回流（设计 §8）")
public class AuditLabelController {

    /** 单次导出上限（防止一次拉爆内存） */
    private static final int MAX_LIMIT = 10000;

    @Resource
    private com.loan.mapper.AuditRecordMapper auditRecordMapper;

    @GetMapping("/export")
    @Operation(summary = "导出已标注样本",
            description = "只返回已填写 final_label 的审核记录；含特征快照，供离线训练回灌。"
                    + "finalLabel: 1=欺诈 / 0=正常")
    public Result<?> export(@RequestParam(value = "limit", defaultValue = "1000") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<Map<String, Object>> samples = auditRecordMapper.selectLabeledSamples(safeLimit);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", samples.size());
        data.put("samples", samples);
        data.put("note", "finalLabel: 1=欺诈 / 0=正常；featureSnapshot 为 JSON 字符串（含当时装配的全部特征）");
        return Result.success(data);
    }
}
