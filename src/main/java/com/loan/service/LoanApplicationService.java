package com.loan.service;
import com.loan.entity.dto.LoanApplyDTO;
import com.loan.entity.dto.UserBasicInfoDTO;
import com.loan.entity.vo.LoanApplicationVO;
import com.loan.entity.vo.Result;
import java.util.List;

public interface LoanApplicationService {
    // 提交贷款申请
    Result<?> submitApply(LoanApplyDTO applyDTO, String userId);

    //提交完整信息的贷款申请
    Result<?> submitApplyWithBasicInfo(UserBasicInfoDTO basicInfo, String userId);

    // 查询申请进度（根据用户ID）
    Result<List<LoanApplicationVO>> queryApplyProgress(String userId);
}