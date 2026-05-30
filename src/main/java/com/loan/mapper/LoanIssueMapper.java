package com.loan.mapper;

import com.loan.entity.LoanIssue;
import org.apache.ibatis.annotations.Param;

public interface LoanIssueMapper {
    // 保存放款记录
    int insert(LoanIssue loanIssue);

    // 根据申请ID查询放款记录（避免重复放款）
    LoanIssue selectByApplicationId(@Param("applicationId") String applicationId);
}