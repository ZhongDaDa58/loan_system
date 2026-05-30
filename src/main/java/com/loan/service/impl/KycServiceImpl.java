package com.loan.service.impl;

import com.loan.entity.MockBankCardPool;
import com.loan.entity.UserFaceLog;
import com.loan.entity.UserIdentity;
import com.loan.entity.vo.KycResultVO;
import com.loan.exception.BusinessException;
import com.loan.mapper.MockBankCardPoolMapper;
import com.loan.mapper.SysUserMapper;
import com.loan.mapper.UserFaceLogMapper;
import com.loan.mapper.UserIdentityMapper;
import com.loan.service.KycService;
import com.loan.util.FileStorageUtil;
import com.loan.util.IdCardValidator;
import com.loan.util.MockOcrUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

@Service
public class KycServiceImpl implements KycService {

    @Resource private UserIdentityMapper identityMapper;
    @Resource private UserFaceLogMapper faceLogMapper;
    @Resource private SysUserMapper sysUserMapper; // 用于更新用户实名状态
    @Resource private FileStorageUtil fileStorageUtil;
    @Resource private UserIdentityMapper userIdentityMapper;
    @Resource private MockBankCardPoolMapper mockBankCardPoolMapper;
    @Override
    public KycResultVO recognizeIdCard(String userId, String imageBase64, String side) {
        KycResultVO result = new KycResultVO();
        try {
            // 1. 保存图片
            String imageUrl = fileStorageUtil.saveSignature(imageBase64); // 复用之前的工具

            // 2. 模拟 OCR 识别
            boolean isFront = "front".equalsIgnoreCase(side);
            Map<String, String> ocrData = MockOcrUtil.recognize(isFront);

            // 3. 将 URL 放入返回结果
            ocrData.put("imageUrl", imageUrl);
            result.setSuccess(true);
            result.setData(ocrData);
            result.setMessage("OCR 识别成功");
        } catch (IOException e) {
            result.setSuccess(false);
            result.setMessage("图片处理失败");
        }
        return result;
    }

    @Override
    @Transactional
    public KycResultVO verifyFace(String userId, String imageBase64) {
        KycResultVO result = new KycResultVO();
        try {
            // 1. 保存人脸抓拍图
            String imageUrl = fileStorageUtil.saveSignature(imageBase64);

            // 2. 模拟活体检测和比对 (随机生成 85-99 分)
            Random random = new Random();
            BigDecimal score = new BigDecimal(85 + random.nextInt(15));
            boolean isPass = score.doubleValue() >= 80;

            // 3. 记录日志
            UserFaceLog log = new UserFaceLog();
            log.setUserId(userId);
            log.setFaceImageUrl(imageUrl);
            log.setSimilarityScore(score);
            log.setIsLive(1);
            log.setResult(isPass ? "PASS" : "FAIL");
            log.setCreateTime(new Date());
            faceLogMapper.insert(log);

            result.setSuccess(isPass);
            result.setMessage(isPass ? "人脸验证通过" : "人脸相似度不足");
            Map<String, Object> resultData = new HashMap<>();
            resultData.put("score", score);       // ⭐ 相似度百分比
            resultData.put("faceUrl", imageUrl);   // ⭐ 图片路径
            result.setData(resultData);
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage("系统异常");
        }
        return result;
    }

    @Override
    @Transactional
    public void submitIdentity(String userId, String realName, String idCardNumber, String frontUrl, String factureUrl) {
        // 1. 校验身份证号格式
        if (!IdCardValidator.isValid(idCardNumber)) {
            throw new RuntimeException("身份证号码格式不正确");
        }

        // 2. 保存认证信息
        UserIdentity identity = new UserIdentity();
        identity.setUserId(userId);
        identity.setUserRealName(realName);
        identity.setIdCardNumber(idCardNumber); // 生产环境建议加密
        identity.setIdCardFrontUrl(frontUrl);
        identity.setFaceCaptureUrl(factureUrl);
        identity.setVerifyStatus(1); // 直接设为已通过（因为是模拟）
        identityMapper.insert(identity);

        // 3. 更新 sys_user 表状态
        sysUserMapper.updateKycStatus(userId, 1, realName);
    }
    @Override
    public KycResultVO verifyFourFactor(String realName, String idCardNumber, String bankCardNo, String phone) {
        KycResultVO result = new KycResultVO();

        // 1. 基础格式校验
        if (!IdCardValidator.isValid(idCardNumber)) {
            result.setSuccess(false);
            result.setMessage("身份证号码格式错误");
            return result;
        }

        // 2. ⭐ 核心逻辑：查询模拟银行数据池
        MockBankCardPool poolCard = mockBankCardPoolMapper.selectByCardNumber(bankCardNo);

        if (poolCard == null) {
            result.setSuccess(false);
            result.setMessage("该银行卡不在支持列表中，请使用系统提供的测试卡号");
            return result;
        }

        // 3. ⭐ 四要素精确比对 (模拟银行网关校验)
        // 注意：这里要求输入的信息必须与数据库 mock_bank_card_pool 表里的预设值完全一致
        if (!poolCard.getCardholderName().equals(realName)) {
            result.setSuccess(false);
            result.setMessage("持卡人姓名与银行预留信息不符");
            return result;
        }

        if (!poolCard.getIdCard().equals(idCardNumber)) {
            result.setSuccess(false);
            result.setMessage("身份证号与银行预留信息不符");
            return result;
        }

        if (!poolCard.getPhone().equals(phone)) {
            result.setSuccess(false);
            result.setMessage("预留手机号不正确，请检查后重试");
            return result;
        }

        // 4. 全部比对通过
        result.setSuccess(true);
        result.setMessage("四要素验证通过，身份可信度已确认");
        result.setData(null); // 可以返回一些脱敏的银行信息，如 bankName

        return result;
    }

    @Override
    public String getVerifiedRealName(String userId) {
        UserIdentity identity = userIdentityMapper.selectByUserId(userId);
        if (identity == null || identity.getVerifyStatus() != 1) {
            throw new BusinessException(403, "用户未完成实名认证");
        }
        return identity.getUserRealName();
    }

}
