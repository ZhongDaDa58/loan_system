package com.loan.service.impl;

import com.loan.entity.UserAgreement;
import com.loan.entity.UserAgreementSign;
import com.loan.entity.vo.AgreementVO;
import com.loan.entity.vo.Result;
import com.loan.exception.BusinessException;
import com.loan.mapper.UserAgreementMapper;
import com.loan.mapper.UserAgreementSignMapper;
import com.loan.service.AgreementService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class AgreementServiceImpl implements AgreementService {

    private static final Logger log = LoggerFactory.getLogger(AgreementServiceImpl.class);

    @Resource
    private UserAgreementMapper userAgreementMapper;

    @Resource
    private UserAgreementSignMapper userAgreementSignMapper;

    // 必需的协议类型列表
    private static final List<String> REQUIRED_AGREEMENT_TYPES = List.of(
            "PRIVACY_POLICY",
            "USER_SERVICE",
            "CREDIT_AUTH",
            "E_SIGNATURE"
    );

    @Override
    public Result<List<AgreementVO>> getAgreementList(String userId) {
        log.info("📋 获取用户 {} 的协议列表", userId);

        // 1. 查询所有当前生效的协议
        List<UserAgreement> agreements = userAgreementMapper.selectCurrentAgreements();

        // 2. 查询用户已签署的协议
        List<UserAgreementSign> signedAgreements = userAgreementSignMapper.selectByUserId(userId);

        // 3. 组装返回数据
        List<AgreementVO> voList = new ArrayList<>();
        for (UserAgreement agreement : agreements) {
            AgreementVO vo = convertToVO(agreement);

            // 检查是否已签署
            boolean hasSigned = signedAgreements.stream()
                    .anyMatch(sign -> sign.getAgreementId().equals(agreement.getAgreementId()));
            vo.setHasSigned(hasSigned);

            // 如果已签署，获取签署时间
            if (hasSigned) {
                signedAgreements.stream()
                        .filter(sign -> sign.getAgreementId().equals(agreement.getAgreementId()))
                        .findFirst()
                        .ifPresent(sign -> vo.setSignTime(sign.getSignTime()));
            }

            voList.add(vo);
        }

        return Result.success(voList);
    }

    @Override
    public Result<AgreementVO> getAgreementDetail(Long agreementId, String userId) {
        log.info("📖 查看协议详情: agreementId={}, userId={}", agreementId, userId);

        UserAgreement agreement = userAgreementMapper.selectById(agreementId);
        if (agreement == null) {
            throw new BusinessException(404, "协议不存在");
        }

        AgreementVO vo = convertToVO(agreement);

        // 检查是否已签署
        UserAgreementSign sign = userAgreementSignMapper.selectByUserAndAgreement(userId, agreementId);
        vo.setHasSigned(sign != null);
        if (sign != null) {
            vo.setSignTime(sign.getSignTime());
        }

        return Result.success(vo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> signAgreement(String userId, Long agreementId, String signatureImageUrl,
                                   String ipAddress, String deviceInfo) {
        log.info("✍️ 用户 {} 签署协议 {}", userId, agreementId);

        // 1. 验证协议是否存在且为当前版本
        UserAgreement agreement = userAgreementMapper.selectById(agreementId);
        if (agreement == null) {
            throw new BusinessException(404, "协议不存在");
        }
        if (agreement.getIsCurrent() != 1) {
            throw new BusinessException(400, "该协议版本已过期，请签署最新版本");
        }

        // 2. 检查是否已签署过
        UserAgreementSign existingSign = userAgreementSignMapper.selectByUserAndAgreement(userId, agreementId);
        if (existingSign != null) {
            throw new BusinessException(400, "您已签署过该协议，无需重复签署");
        }

        // 3. 创建签署记录
        UserAgreementSign sign = new UserAgreementSign();
        sign.setUserId(userId);
        sign.setAgreementId(agreementId);
        sign.setAgreementType(agreement.getAgreementType());
        sign.setAgreementVersion(agreement.getVersion());
        sign.setSignatureImageUrl(signatureImageUrl);
        sign.setSignTime(new Date());
        sign.setIpAddress(ipAddress);
        sign.setDeviceInfo(deviceInfo != null ? deviceInfo : "Web");
        sign.setIsValid(1);

        int rows = userAgreementSignMapper.insert(sign);
        if (rows != 1) {
            throw new BusinessException(500, "签署失败，请重试");
        }

        log.info("✅ 用户 {} 成功签署协议 {}", userId, agreementId);

        return Result.success("协议签署成功");
    }

    @Override
    public Result<Boolean> checkAllAgreementsSigned(String userId) {
        log.info("🔍 检查用户 {} 是否完成所有协议签署", userId);

        boolean allSigned = true;
        for (String agreementType : REQUIRED_AGREEMENT_TYPES) {
            UserAgreementSign sign = userAgreementSignMapper.selectCurrentVersionSign(userId, agreementType);
            if (sign == null) {
                allSigned = false;
                log.warn("⚠️ 用户 {} 未签署协议类型: {}", userId, agreementType);
                break;
            }
        }

        return Result.success(allSigned);
    }

    @Override
    public Result<List<?>> getMySignHistory(String userId) {
        log.info("📜 获取用户 {} 的签署历史", userId);

        List<UserAgreementSign> signs = userAgreementSignMapper.selectByUserId(userId);

        // 转换为简化VO（可根据需要创建专门的VO类）
        List<AgreementVO> history = signs.stream().map(sign -> {
            UserAgreement agreement = userAgreementMapper.selectById(sign.getAgreementId());
            if (agreement != null) {
                AgreementVO vo = convertToVO(agreement);
                vo.setHasSigned(true);
                vo.setSignTime(sign.getSignTime());
                return vo;
            }
            return null;
        }).filter(vo -> vo != null).collect(Collectors.toList());

        return Result.success(history);
    }

    /**
     * 实体转VO
     */
    private AgreementVO convertToVO(UserAgreement agreement) {
        AgreementVO vo = new AgreementVO();
        vo.setAgreementId(agreement.getAgreementId());
        vo.setAgreementType(agreement.getAgreementType());
        vo.setAgreementTypeDesc(getAgreementTypeDesc(agreement.getAgreementType()));
        vo.setVersion(agreement.getVersion());
        vo.setTitle(agreement.getTitle());
        vo.setSummary(agreement.getSummary());
        vo.setContent(agreement.getContent());
        vo.setEffectiveDate(agreement.getEffectiveDate());
        return vo;
    }

    /**
     * 获取协议类型描述
     */
    private String getAgreementTypeDesc(String type) {
        return switch (type) {
            case "PRIVACY_POLICY" -> "隐私政策";
            case "USER_SERVICE" -> "用户服务协议";
            case "CREDIT_AUTH" -> "征信授权书";
            case "E_SIGNATURE" -> "电子签名协议";
            default -> "未知协议";
        };
    }
}
