package com.loan.service.impl;

import lombok.extern.slf4j.Slf4j;

import com.loan.entity.AuditRecord;
import com.loan.entity.LoanApplication;
import com.loan.entity.UserCreditProfile;
import com.loan.entity.dto.AuditSubmitDTO;
import com.loan.entity.enums.ApplicationStatusEnum;
import com.loan.entity.enums.AuditResultEnum;
import com.loan.entity.vo.LoanApplicationVO;
import com.loan.entity.vo.PendingAuditVO;
import com.loan.entity.vo.RepaymentRecordVO;
import com.loan.entity.vo.Result;
import com.loan.entity.vo.loanDetail.LoanHistoryRecordVO;
import com.loan.entity.vo.loanDetail.LoanHistorySummaryVO;
import com.loan.entity.vo.loanDetail.LoanHistoryVO;
import com.loan.exception.BusinessException;
import com.loan.mapper.AuditRecordMapper;
import com.loan.mapper.LoanApplicationMapper;
import com.loan.mapper.MonthlyRepaymentMapper;
import com.loan.mapper.UserCreditProfileMapper;
import com.loan.service.AuditService;
import com.loan.service.ContractService;
import com.loan.service.NotificationService;
import com.loan.util.IdUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AuditServiceImpl implements AuditService {

    @Resource
    private AuditRecordMapper auditRecordMapper;

    @Resource
    private LoanApplicationMapper loanApplicationMapper;

    /** 阶段四 4.3 补：幂等控制（防审核提交重复投递） */
    @Resource
    private com.loan.service.IdempotencyService idempotencyService;

    @Resource
    private ContractService contractService; // ⭐ 注入合同服务

    /** 业务指标（阶段一 1.5：合同生成失败可告警，替代原先只有 System.err 的静默失败） */
    @Resource
    private com.loan.metrics.RiskMetrics riskMetrics;

    @Resource
    private MonthlyRepaymentMapper monthlyRepaymentMapper;

    @Resource
    private NotificationService notificationService;

    @Resource
    private UserCreditProfileMapper userCreditProfileMapper;

    @Override
    public Result<List<PendingAuditVO>> queryPendingList() {
        List<PendingAuditVO> pendingList = auditRecordMapper.selectPendingList();
        return Result.success(pendingList);
    }

    @Override
    public Result<List<LoanApplicationVO>> queryPassedList() {
        List<LoanApplicationVO> passedList = loanApplicationMapper.selectAllPassed();
        return Result.success(passedList);
    }

    @Override
    public Result<?> submitAudit(AuditSubmitDTO auditDTO, String auditorId) {
        // 阶段四 4.3 补：幂等检查（第 ② 层，防同一审核员重复投递）
        // 注意：幂等键只防"同一请求重放"，真正的并发防护在下面的条件更新
        idempotencyService.acquire("audit", auditorId);

        // 1. 校验申请是否存在
        LoanApplication application = loanApplicationMapper.selectByApplicationId(auditDTO.getApplicationId());
        if (application == null) {
            throw new BusinessException(400, "贷款申请不存在");
        }

        // 2. 校验申请状态是否为待审核（快路径：先给友好提示，真正的并发防护在步骤 5）
        if (!ApplicationStatusEnum.PENDING.getCode().equals(application.getApplicationStatus())) {
            throw new BusinessException(400, "该申请已审核，无需重复操作");
        }

        // 3. 校验审核结果是否合法
        String auditResult = auditDTO.getAuditResult();
        if (!AuditResultEnum.APPROVED.getCode().equals(auditResult)
                && !AuditResultEnum.REJECTED.getCode().equals(auditResult)
                && !AuditResultEnum.SUPPLEMENT.getCode().equals(auditResult)) {
            throw new BusinessException(400, "审核结果不合法（仅支持approved/rejected/supplement）");
        }

        // 4. 映射申请状态（审核结果→申请状态）
        String applicationStatus = switch (auditResult) {
            case "approved" -> ApplicationStatusEnum.APPROVED.getCode();
            case "rejected" -> ApplicationStatusEnum.REJECTED.getCode();
            case "supplement" -> ApplicationStatusEnum.SUPPLEMENT.getCode();
            default -> throw new BusinessException(400, "未知审核结果");
        };

        // 5. 更新贷款申请状态（★ 条件更新：只有当前仍是 pending 才更新）
        //    为什么不用 updateStatus：它是"先读后写"，两个审核员并发提交时
        //    都能通过步骤 2 的检查，然后互相覆盖（还会插两条 audit_record）。
        //    这里把"检查 + 更新"压成一条原子 SQL，靠影响行数判断谁抢到。
        int updateRows = loanApplicationMapper.updateStatusIfCurrent(
                auditDTO.getApplicationId(),
                ApplicationStatusEnum.PENDING.getCode(),
                applicationStatus);
        if (updateRows != 1) {
            // 0 行 = 状态已被他人改变（并发提交，或步骤 2 之后被别人抢先）
            log.warn("⚠️ 审核状态更新失败，可能已被他人处理: applicationId={}, auditorId={}",
                    auditDTO.getApplicationId(), auditorId);
            throw new BusinessException(400, "该申请已被他人审核，请刷新后重试");
        }

        // 6. 保存审核记录
        AuditRecord auditRecord = new AuditRecord();
        auditRecord.setAuditId(IdUtil.generateId());
        auditRecord.setApplicationId(auditDTO.getApplicationId());
        auditRecord.setAuditorId(auditorId);
        auditRecord.setAuditResult(auditResult);
        auditRecord.setAuditOpinion(auditDTO.getAuditOpinion() == null ? "无" : auditDTO.getAuditOpinion());
        auditRecord.setAuditTime(new Date());
        // S1：Y 标签落库（设计 §8「人工结论回流训练库」；三项均可选填）
        auditRecord.setFinalLabel(auditDTO.getFinalLabel());
        auditRecord.setFraudTag(auditDTO.getFraudTag());
        auditRecord.setRejectReason(auditDTO.getRejectReason());

        auditRecordMapper.insert(auditRecord);

        // 发送审核结果通知
        String userId = application.getUserId();
        switch (auditResult) {
            case "approved":
                // 生成合同
                try {
                    contractService.generateDraftContract(auditDTO.getApplicationId());
                } catch (Exception e) {
                    // 保留堆栈：合同没生成 = 用户无法签约 = 业务流程中断，必须能事后追溯
                    log.error("审核通过但合同生成失败 applicationId={}", auditDTO.getApplicationId(), e);
                    // 1.5：指标化 —— 可配告警（原先只有 System.err，重启即丢、无人知晓）
                    riskMetrics.contractFailed();
                }
                // 通知用户签署合同
                notificationService.sendNotification(
                        userId,
                        "贷款申请已通过",
                        "您的贷款申请已审核通过，请尽快完成合同签署。",
                        "system",
                        auditDTO.getApplicationId()
                );
                break;

            case "rejected":
                // 通知用户申请被拒
                String opinion = auditDTO.getAuditOpinion() != null ? auditDTO.getAuditOpinion() : "未通过风控审核";
                notificationService.sendNotification(
                        userId,
                        "贷款申请未通过",
                        "您的贷款申请未通过审核，原因：" + opinion,
                        "system",
                        auditDTO.getApplicationId()
                );
                break;

            default:
                // supplement 等其他结果暂不发送通知
                break;
        }

        return Result.success("审核结果提交成功");
    }

    // ========== 历史借贷模块 ==========

    @Override
    public Result<LoanHistoryVO> getLoanHistory(String applicationId) {
        // 1. 获取当前申请信息
        LoanApplication current = loanApplicationMapper.selectByApplicationId(applicationId);
        if (current == null) {
            return Result.error(404, "申请记录不存在");
        }

        // 2. 查询历史贷款记录（当前申请之前的）
        List<LoanHistoryRecordVO> records = loanApplicationMapper.selectLoanHistoryByUser(
                current.getUserId(), current.getApplyTime(), applicationId);

        // 3. 收集历史申请的 applicationId 集合
        Set<String> historyAppIds = records.stream()
                .map(LoanHistoryRecordVO::getApplicationId)
                .collect(Collectors.toSet());

        // 4. 查询该用户的全部还款记录，筛选出属于历史贷款的
        List<RepaymentRecordVO> allRepayments = monthlyRepaymentMapper.selectRepaymentRecordByUserId(current.getUserId());
        List<RepaymentRecordVO> historyRepayments = allRepayments.stream()
                .filter(r -> historyAppIds.contains(r.getLoanId()))
                .collect(Collectors.toList());

        // 5. 计算汇总统计
        LoanHistorySummaryVO summary = new LoanHistorySummaryVO();
        summary.setTotalApplications(records.size());
        summary.setApprovedCount((int) records.stream()
                .filter(r -> "approved".equals(r.getStatus()) || "issued".equals(r.getStatus()))
                .count());
        summary.setRejectedCount((int) records.stream()
                .filter(r -> "rejected".equals(r.getStatus()))
                .count());
        summary.setTotalBorrowed(records.stream()
                .filter(r -> "approved".equals(r.getStatus()) || "issued".equals(r.getStatus()))
                .map(r -> r.getAmount() != null ? r.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        summary.setTotalRepaid(historyRepayments.stream()
                .filter(r -> "paid".equals(r.getStatus()))
                .map(r -> r.getAmount() != null ? r.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        summary.setOverdueCount((int) historyRepayments.stream()
                .filter(r -> "overdue".equals(r.getStatus()))
                .count());

        // 6. 转换状态：issued + 全部还清 → completed，issued + 未还清 → repaying
        for (LoanHistoryRecordVO record : records) {
            String rawStatus = record.getStatus();
            if ("issued".equals(rawStatus)) {
                // 检查是否全部还清
                boolean allPaid = historyRepayments.stream()
                        .filter(r -> r.getLoanId().equals(record.getApplicationId()))
                        .allMatch(r -> "paid".equals(r.getStatus()));
                record.setStatus(allPaid ? "completed" : "repaying");
            } else if ("approved".equals(rawStatus) || "pending".equals(rawStatus) || "supplement".equals(rawStatus)) {
                record.setStatus("cancelled");
            }
            // rejected 保持不变
        }

        return Result.success(new LoanHistoryVO(summary, records));
    }

    @Override
    public Result<UserCreditProfile> getApplicantCreditProfile(String applicationId) {
        // 获取申请信息
        LoanApplication application = loanApplicationMapper.selectByApplicationId(applicationId);
        if (application == null) {
            return Result.error(404, "申请记录不存在");
        }

        // 查询申请人信用档案
        UserCreditProfile profile = userCreditProfileMapper.selectByUserId(application.getUserId());
        if (profile == null) {
            return Result.error(404, "申请人尚未创建信用档案");
        }

        return Result.success(profile);
    }
}