package com.loan.controller;

import com.loan.entity.UserIdentity;
import com.loan.entity.dto.FourFactorVerifyDTO;
import com.loan.entity.dto.KycOcrDTO;
import com.loan.entity.dto.KycSubmitDTO;
import com.loan.entity.vo.KycResultVO;
import com.loan.entity.vo.Result;
import com.loan.mapper.UserIdentityMapper;
import com.loan.service.KycService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/kyc")
@Tag(name = "实名认证模块", description = "OCR识别、人脸验证、实名提交")
public class KycController {

    @Resource
    private KycService kycService;
    @Resource
    private UserIdentityMapper userIdentityMapper;

    /**
     * 1. OCR 识别身份证
     */
    @PostMapping("/idcard/ocr")
    @Operation(summary = "OCR识别身份证", description = "上传身份证照片，返回识别出的姓名和身份证号")
    public Result<KycResultVO> ocrIdCard(@Valid @RequestBody KycOcrDTO ocrDTO, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        KycResultVO res = kycService.recognizeIdCard(userId, ocrDTO.getImageBase64(), ocrDTO.getSide());
        return Result.success(res);
    }

    /**
     * 2. 人脸识别验证
     */
    @PostMapping("/face/verify")
    @Operation(summary = "人脸验证", description = "上传人脸照片，模拟活体检测并返回相似度")
    public Result<KycResultVO> verifyFace(@RequestBody KycOcrDTO faceDTO, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        KycResultVO res = kycService.verifyFace(userId, faceDTO.getImageBase64());
        return Result.success(res);
    }

    /**
     * 3. 提交实名认证信息
     */
    @PostMapping("/submit")
    @Operation(summary = "提交实名认证", description = "保存实名信息并激活账户")
    public Result<?> submitKyc(@Valid @RequestBody KycSubmitDTO submitDTO, HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");

        // 调用 Service 层进行保存和状态更新
        kycService.submitIdentity(
                userId,
                submitDTO.getRealName(),
                submitDTO.getIdCardNumber(),
                submitDTO.getFrontUrl(),
                submitDTO.getFaceCaptureUrl()
        );

        return Result.success("认证成功");
    }
    /**
     * 4. 四要素验证 (模拟银行接口)
     */
    @PostMapping("/four-factor/verify")
    @Operation(summary = "四要素验证", description = "校验姓名、身份证、银行卡、手机号一致性")
    public Result<KycResultVO> verifyFourFactor(@Valid @RequestBody FourFactorVerifyDTO verifyDTO,
                                                HttpServletRequest request) {
        KycResultVO res = kycService.verifyFourFactor(
                verifyDTO.getRealName(),
                verifyDTO.getIdCardNumber(),
                verifyDTO.getBankCardNo(),
                verifyDTO.getPhone()
        );
        return Result.success(res);


    }
    /**
     * 5. 获取用户实名信息 (用于绑卡自动填充)
     */
    @GetMapping("/my-info")
    @Operation(summary = "获取我的实名信息", description = "返回已认证的姓名和脱敏后的身份证号")
    public Result<?> getMyKycInfo(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");

        UserIdentity identity = userIdentityMapper.selectByUserId(userId);

        if (identity == null || identity.getVerifyStatus() != 1) {
            return Result.error(400, "您尚未完成实名认证");
        }

        Map<String, Object> info = new HashMap<>();
        info.put("realName", identity.getUserRealName());

        // ⭐ 身份证号脱敏处理：保留前3位和后4位，中间用 **** 代替
        String idCard = identity.getIdCardNumber();
        String maskedIdCard = idCard.replaceAll("(\\d{3})\\d{11}(\\w{4})", "$1****$2");
        info.put("idCardNumber", maskedIdCard);

        return Result.success(info);
    }
}
