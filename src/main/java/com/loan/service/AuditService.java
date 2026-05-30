package com.loan.service;
import com.loan.entity.dto.AuditSubmitDTO;
import com.loan.entity.vo.LoanApplicationVO;
import com.loan.entity.vo.PendingAuditVO;
import com.loan.entity.vo.Result;
import java.util.List;

public interface AuditService {
    // 查询待审核列表（审核员用）
    Result<List<PendingAuditVO>> queryPendingList();

    // 查询审核通过列表（审核员用）
    Result<List<LoanApplicationVO>> queryPassedList();

    // 提交审核结果（审核员用）
    Result<?> submitAudit(AuditSubmitDTO auditDTO, String auditorId);
}