package com.loan.service;

import com.loan.entity.vo.AgreementVO;
import com.loan.entity.vo.Result;
import java.util.List;

public interface AgreementService {

    /**
     * 获取所有需要签署的协议列表（检查用户是否已签署）
     */
    Result<List<AgreementVO>> getAgreementList(String userId);

    /**
     * 查看协议详情
     */
    Result<AgreementVO> getAgreementDetail(Long agreementId, String userId);

    /**
     * 签署协议
     */
    Result<?> signAgreement(String userId, Long agreementId, String signatureImageUrl,
                            String ipAddress, String deviceInfo);

    /**
     * 检查用户是否已完成所有必需协议的签署
     */
    Result<Boolean> checkAllAgreementsSigned(String userId);

    /**
     * 获取用户签署历史
     */
    Result<List<?>> getMySignHistory(String userId);
}
