package com.loan.service;
import com.loan.entity.dto.LoanIssueDTO;
import com.loan.entity.vo.Result;

public interface LoanIssueService {
    // 执行放款
    Result<?> executeIssue(LoanIssueDTO issueDTO);
}