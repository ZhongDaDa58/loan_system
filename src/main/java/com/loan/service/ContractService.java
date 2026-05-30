package com.loan.service;

import com.loan.entity.vo.Result;

public interface ContractService {
    /**
     * 阶段一：生成基础合同（无章无签）
     */
    Result<String> generateDraftContract(String applicationId);

    /**
     * 阶段二：用户电子签名
     */
    Result<String> signByUser(String applicationId);

    /**
     * 阶段三：平台盖章并生效（通常在放款时触发）
     */
    Result<String> stampAndActivate(String applicationId);

    /**
     * 查询合同详情（包含状态、路径、时间等）
     */
    Result<com.loan.entity.LoanApplication> getContractStatus(String applicationId);

}
