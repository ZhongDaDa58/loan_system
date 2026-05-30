package com.loan.service;

import com.loan.entity.vo.KycResultVO;
import java.util.Map;

public interface KycService {
    // 1. 模拟 OCR 识别身份证
    KycResultVO recognizeIdCard(String userId, String imageBase64, String side);

    // 2. 模拟人脸识别验证
    KycResultVO verifyFace(String userId, String imageBase64);

    // 3. 提交实名认证信息
    void submitIdentity(String userId, String realName, String idCardNumber, String frontUrl, String backUrl);

    // 接口中增加
    KycResultVO verifyFourFactor(String realName, String idCardNumber, String bankCardNo, String phone);

    public String getVerifiedRealName(String userId);
}
