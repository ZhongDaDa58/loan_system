package com.loan.controller;
import com.loan.entity.dto.AuditSubmitDTO;
import com.loan.entity.vo.LoanApplicationVO;
import com.loan.entity.vo.LoanProductVO;
import com.loan.entity.vo.PendingAuditVO;
import com.loan.entity.vo.Result;
import com.loan.entity.vo.loanDetail.ApplicantIdentityVO;
import com.loan.entity.vo.loanDetail.LoanApplicationBasicVO;
import com.loan.entity.vo.loanDetail.LoanApplicationDetailVO;
import com.loan.entity.vo.loanDetail.ScorecardDetailVO;
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




    @Resource
    private LoanApplicationService loanApplicationService;

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
    /**
            * 查询单笔申请详情（审批端使用）
            */
    @GetMapping("/{applicationId}/basic")
    @Operation(summary = "查询单笔申请基本信息详情", description = "根据申请ID查询完整的申请信息，用于审批详情页展示")
    public Result<LoanApplicationBasicVO> getApplicationDetail(@PathVariable String applicationId) {
        return loanApplicationService.getApplicationBasic(applicationId);
    }

    /**
     * 查询单笔申请详情（审批端使用）
     */
    @GetMapping("/{applicationId}/applicant")
    @Operation(summary = "查询单笔申请 applicant 信息详情", description = "根据申请ID查询完整的申请信息，用于审批详情页展示")
    public Result<ApplicantIdentityVO> getApplicantIdentity(@PathVariable String applicationId) {
        return loanApplicationService.getApplicantIdentity(applicationId);
    }

    /**
     * 查询评分卡结果详情（审批端使用）
     */
    @GetMapping("/{applicationId}/scorecard")
    @Operation(summary = "查询评分卡结果详情", description = "查询申请的评分卡详细结果，包含总评分和六个核心维度的分数")
    public Result<ScorecardDetailVO> getScorecardDetail(@PathVariable String applicationId) {
        return loanApplicationService.getScorecardDetail(applicationId);
    }
}