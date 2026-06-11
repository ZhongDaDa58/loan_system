package com.loan.service.impl;

import com.loan.entity.AuditRecord;
import com.loan.entity.LoanApplication;
import com.loan.entity.dto.AuditSubmitDTO;
import com.loan.entity.enums.ApplicationStatusEnum;
import com.loan.entity.enums.AuditResultEnum;
import com.loan.entity.vo.LoanApplicationVO;
import com.loan.entity.vo.PendingAuditVO;
import com.loan.entity.vo.Result;
import com.loan.exception.BusinessException;
import com.loan.mapper.AuditRecordMapper;
import com.loan.mapper.LoanApplicationMapper;
import com.loan.service.AuditService;
import com.loan.service.ContractService;
import com.loan.util.IdUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service
public class AuditServiceImpl implements AuditService {

    @Resource
    private AuditRecordMapper auditRecordMapper;

    @Resource
    private LoanApplicationMapper loanApplicationMapper;

    @Resource
    private ContractService contractService; // ⭐ 注入合同服务

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
        // 1. 校验申请是否存在
        LoanApplication application = loanApplicationMapper.selectByApplicationId(auditDTO.getApplicationId());
        if (application == null) {
            throw new BusinessException(400, "贷款申请不存在");
        }

        // 2. 校验申请状态是否为待审核
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

        // 5. 更新贷款申请状态
        int updateRows = loanApplicationMapper.updateStatus(auditDTO.getApplicationId(), applicationStatus);
        if (updateRows != 1) {
            throw new BusinessException(500, "审核结果提交失败，请重试");
        }

        // 6. 保存审核记录
        AuditRecord auditRecord = new AuditRecord();
        auditRecord.setAuditId(IdUtil.generateId());
        auditRecord.setApplicationId(auditDTO.getApplicationId());
        auditRecord.setAuditorId(auditorId);
        auditRecord.setAuditResult(auditResult);
        auditRecord.setAuditOpinion(auditDTO.getAuditOpinion() == null ? "无" : auditDTO.getAuditOpinion());
        auditRecord.setAuditTime(new Date());

        auditRecordMapper.insert(auditRecord);
        // 如果审核结果为 approved，则生成合同草稿（合同由 contractService 处理）
        if ("approved".equals(auditResult)) {
            try {
                contractService.generateDraftContract(auditDTO.getApplicationId());
            } catch (Exception e) {
                // 即使合同生成失败，也不应该回滚审核结果，但可以记录日志
                System.err.println("⚠️ 审核通过但合同生成失败: " + e.getMessage());
            }
        }
        return Result.success("审核结果提交成功");
    }
}