package com.loan.controller;
import com.loan.entity.dto.AuditSubmitDTO;
import com.loan.entity.vo.LoanApplicationVO;
import com.loan.entity.vo.LoanProductVO;
import com.loan.entity.vo.PendingAuditVO;
import com.loan.entity.vo.Result;
import com.loan.service.AuditService;
import com.loan.service.LoanApplicationService;
import com.loan.service.LoanProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/loan/audit")
@Tag(name = "审核模块", description = "查询待审核列表、提交审核结果（审核员专用）")
public class AuditController {

    @Resource
    private AuditService auditService;



    /**
     * 查询待审核列表（需登录，后续加审核员权限校验）
     */
    @GetMapping("/pending-list")
    @Operation(summary = "查询待审核列表", description = "返回所有状态为待审核的贷款申请")
    public Result<List<PendingAuditVO>> queryPendingList(HttpServletRequest request) {
        // 后续添加：校验当前用户是否为审核员（role=admin）
        return auditService.queryPendingList();
    }

    /**
     * 查询审批通过列表（需登录，后续加审核员权限校验）
     */
    @GetMapping("/passed-list")
    @Operation(summary = "查询审批通过列表", description = "返回所有状态为通过审核的贷款申请")
    public Result<List<LoanApplicationVO>> queryPassedList(HttpServletRequest request) {
        // 后续添加：校验当前用户是否为审核员（role=admin）
        return auditService.queryPassedList();
    }

    /**
     * 提交审核结果（需登录）
     */
    @PostMapping("/submit")
    @Operation(summary = "提交审核结果", description = "更新申请状态并保存审核记录")
    public Result<?> submitAudit(@Valid @RequestBody AuditSubmitDTO auditDTO, HttpServletRequest request) {
        String auditorId = (String) request.getAttribute("userId"); // 审核员ID（登录用户）
        return auditService.submitAudit(auditDTO, auditorId);
    }
}