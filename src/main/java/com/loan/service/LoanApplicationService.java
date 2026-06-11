package com.loan.service;
import com.loan.entity.dto.LoanApplyDTO;
import com.loan.entity.dto.UserBasicInfoDTO;
import com.loan.entity.vo.LoanApplicationVO;
import com.loan.entity.vo.Result;
import com.loan.entity.vo.loanDetail.ApplicantIdentityVO;
import com.loan.entity.vo.loanDetail.LoanApplicationBasicVO;
import com.loan.entity.vo.loanDetail.LoanApplicationDetailVO;
import com.loan.entity.vo.loanDetail.ScorecardDetailVO;

import java.util.List;

public interface LoanApplicationService {
    // 提交贷款申请
    Result<?> submitApply(LoanApplyDTO applyDTO, String userId);

    //提交完整信息的贷款申请
    Result<?> submitApplyWithBasicInfo(UserBasicInfoDTO basicInfo, String userId);

    // 查询申请进度（根据用户ID）
    Result<List<LoanApplicationVO>> queryApplyProgress(String userId);

    // 查询申请基本信息（审批端使用）
    Result<LoanApplicationBasicVO> getApplicationBasic(String applicationId);

    // 查询申请人身份信息（审批端使用）
    Result<ApplicantIdentityVO> getApplicantIdentity(String applicationId);

    // 查询评分卡结果详情（审批端使用）
    Result<ScorecardDetailVO> getScorecardDetail(String applicationId);

}