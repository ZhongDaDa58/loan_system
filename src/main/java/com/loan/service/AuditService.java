package com.loan.service;
import com.loan.entity.dto.AuditSubmitDTO;
import com.loan.entity.vo.LoanApplicationVO;
import com.loan.entity.vo.PendingAuditVO;
import com.loan.entity.vo.Result;
import com.loan.entity.UserCreditProfile;
import com.loan.entity.vo.loanDetail.LoanHistoryVO;

import java.util.List;

public interface AuditService {
    // 查询待审核列表（审核员用）
    Result<List<PendingAuditVO>> queryPendingList();

    // 查询审核通过列表（审核员用）
    Result<List<LoanApplicationVO>> queryPassedList();

    // 提交审核结果（审核员用）
    Result<?> submitAudit(AuditSubmitDTO auditDTO, String auditorId);

    // ========== 历史借贷模块 ==========

    /**
     * 获取当前申请人的历史借贷汇总 + 明细（只返回当前申请之前的数据）
     */
    Result<LoanHistoryVO> getLoanHistory(String applicationId);

    /**
     * 获取申请人信用档案（职业、资产、家庭、收入等）
     */
    Result<UserCreditProfile> getApplicantCreditProfile(String applicationId);
}