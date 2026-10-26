package com.loan.controller;

import com.loan.entity.PenaltyWaiver;
import com.loan.entity.vo.Result;
import com.loan.service.PenaltyWaiverService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 罚息减免审批（逾期处理功能升级 第 6 步-d）
 *
 * <p><b>刻意没有提供"直接设置罚息"的接口</b> —— 改罚息只能走这条审批链。
 * 开发文档对这块的原话是「业务常见需求，但要走审批，别直接改数」。
 *
 * <p>路径在 {@code /api/v1/admin/**} 下，自动受 SecurityConfig 的 {@code hasRole("ADMIN")} 保护。
 */
@RestController
@RequestMapping("/api/v1/admin/penalty-waiver")
@Tag(name = "罚息减免", description = "罚息减免的申请与审批（改罚息的唯一合法路径）")
public class PenaltyWaiverController {

    @Resource
    private PenaltyWaiverService penaltyWaiverService;

    @PostMapping
    @Operation(summary = "发起减免申请",
            description = "申请阶段不动罚息，客户仍可正常还款；同一期次只能有一条待审批申请")
    public Result<PenaltyWaiver> apply(
            @RequestParam String repaymentId,
            @RequestParam BigDecimal waivedAmount,
            @RequestParam String reason,
            @RequestParam String applicant) {
        return Result.success(penaltyWaiverService.apply(
                repaymentId, waivedAmount, reason, applicant));
    }

    @PostMapping("/{waiverId}/approve")
    @Operation(summary = "审批通过（唯一会真正减少罚息的操作）",
            description = "审批人不能与申请人相同；自己申请自己批等于没有审批")
    public Result<PenaltyWaiver> approve(
            @PathVariable String waiverId,
            @RequestParam String approver,
            @RequestParam(required = false) String remark) {
        return Result.success(penaltyWaiverService.approve(waiverId, approver, remark));
    }

    @PostMapping("/{waiverId}/reject")
    @Operation(summary = "审批驳回", description = "罚息不变")
    public Result<PenaltyWaiver> reject(
            @PathVariable String waiverId,
            @RequestParam String approver,
            @RequestParam(required = false) String remark) {
        return Result.success(penaltyWaiverService.reject(waiverId, approver, remark));
    }

    @PostMapping("/{waiverId}/cancel")
    @Operation(summary = "申请人撤回", description = "只能撤自己提交的；罚息不变")
    public Result<Map<String, Object>> cancel(
            @PathVariable String waiverId,
            @RequestParam String applicant,
            @RequestParam(required = false) String remark) {
        boolean ok = penaltyWaiverService.cancel(waiverId, applicant, remark);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("success", ok);
        data.put("message", ok ? "已撤回" : "撤回失败：申请已被处理或不是本人提交");
        return Result.success(data);
    }

    @GetMapping
    @Operation(summary = "分页查询减免申请",
            description = "待审批的排在最前，方便审批人先处理该处理的")
    public Result<Map<String, Object>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        List<PenaltyWaiver> items = penaltyWaiverService.list(status, userId, page, size);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", penaltyWaiverService.count(status, userId));
        data.put("page", page);
        data.put("size", size);
        data.put("items", items);
        return Result.success(data);
    }

    @GetMapping("/{waiverId}")
    @Operation(summary = "查看减免申请详情")
    public Result<PenaltyWaiver> detail(@PathVariable String waiverId) {
        PenaltyWaiver waiver = penaltyWaiverService.getByWaiverId(waiverId);
        if (waiver == null) {
            return Result.error(404, "减免申请不存在");
        }
        return Result.success(waiver);
    }
}
