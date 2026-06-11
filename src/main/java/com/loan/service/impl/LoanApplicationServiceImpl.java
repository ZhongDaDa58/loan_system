package com.loan.service.impl;
import com.loan.entity.*;
import com.loan.entity.dto.FeatureTransformationResultDTO;
import com.loan.entity.dto.LoanApplyDTO;
import com.loan.entity.dto.UserBasicInfoDTO;
import com.loan.entity.dto.UserProfileDTO;
import com.loan.entity.enums.ApplicationStatusEnum;
import com.loan.entity.enums.ScorecardResultEnum;
import com.loan.entity.vo.LoanApplicationVO;
import com.loan.entity.vo.Result;
import com.loan.entity.vo.loanDetail.ApplicantIdentityVO;
import com.loan.entity.vo.loanDetail.LoanApplicationBasicVO;
import com.loan.entity.vo.loanDetail.LoanApplicationDetailVO;
import com.loan.entity.vo.loanDetail.ScorecardDetailVO;
import com.loan.exception.BusinessException;
import com.loan.mapper.*;
import com.loan.service.*;
import com.loan.util.IdUtil;
import com.loan.util.RiskControlUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

@Service
public class LoanApplicationServiceImpl implements LoanApplicationService {

    private static final Logger log = LoggerFactory.getLogger(LoanApplicationServiceImpl.class);

    @Resource
    private LoanApplicationMapper loanApplicationMapper;

    @Resource
    private LoanProductService loanProductService;

    @Resource
    private AuditService auditService;

    @Resource
    private MonthlyRepaymentMapper monthlyRepaymentMapper;

    @Resource
    private ScorecardService scorecardService;

    @Resource
    private FeatureTransformationService featureTransformationService;

    @Resource
    private DataValidationService dataValidationService;

    @Resource
    private RiskDecisionLogService riskDecisionLogService;

    @Resource
    private CreditScoreSyncService creditScoreSyncService;

    @Resource
    private UserBankCardMapper userBankCardMapper;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private UserIdentityMapper userIdentityMapper;


    @Override
    public Result<?> submitApply(LoanApplyDTO applyDTO, String userId) {
        log.info("📝 用户 {} 提交贷款申请: 产品={}, 金额={}, 期限={}",
                userId, applyDTO.getProductId(), applyDTO.getApplyAmount(), applyDTO.getApplyTerm());

        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || user.getKycStatus() == null || user.getKycStatus() != 1) {
            throw new BusinessException(403, "您尚未完成实名认证，请先进行身份认证后再申请贷款");
        }

        List<MonthlyRepayment> overdueRecords = monthlyRepaymentMapper.selectOverdueByUserId(userId);
        if (!overdueRecords.isEmpty()) {
            throw new BusinessException(400, "您有" + overdueRecords.size() + "笔逾期未还款记录，请先结清欠款后再申请");
        }

        LoanProduct product = loanProductService.validateProduct(applyDTO.getProductId());

        BigDecimal applyAmount = applyDTO.getApplyAmount();
        Integer applyTerm = applyDTO.getApplyTerm();
        if (applyAmount.compareTo(product.getMinAmount()) < 0 || applyAmount.compareTo(product.getMaxAmount()) > 0) {
            throw new BusinessException(400, "申请金额需在" + product.getMinAmount() + "-" + product.getMaxAmount() + "元之间");
        }
        if (applyTerm < product.getMinTerm() || applyTerm > product.getMaxTerm()) {
            throw new BusinessException(400, "申请期限需在" + product.getMinTerm() + "-" + product.getMaxTerm() + "月之间");
        }

        Long disbursementCardId = applyDTO.getDisbursementCardId();
        if (disbursementCardId != null) {
            UserBankCard bankCard = userBankCardMapper.selectById(disbursementCardId);
            if (bankCard == null) {
                throw new BusinessException(400, "指定的银行卡不存在");
            }
            if (!userId.equals(bankCard.getUserId())) {
                throw new BusinessException(400, "无权使用该银行卡作为放款账户");
            }
            if (!"ACTIVE".equals(bankCard.getBindStatus())) {
                throw new BusinessException(400, "该银行卡已解绑，请选择其他卡片");
            }
            log.info("✅ 用户指定放款银行卡: {}", bankCard.getAliasName());
        } else {
            log.info("ℹ️ 用户未指定放款银行卡，将使用默认卡");
        }

        UserProfileDTO userProfile = buildUserProfileFromApplyDTO(applyDTO);

        log.info("🔍 开始调用评分卡模型计算分数...");
        Integer scorecardScore = scorecardService.calculateScoreOnly(userProfile);
        log.info("✅ 评分卡计算完成，得分: {}", scorecardScore);

        if (product.getAutoPassScore() == null || product.getManualReviewScore() == null) {
            throw new BusinessException(500, "产品评分卡配置不完整，请联系管理员");
        }

        ScorecardResultEnum scorecardResult = RiskControlUtil.evaluateScorecard(
                scorecardScore,
                product.getAutoPassScore(),
                product.getManualReviewScore()
        );

        log.info("🎯 评分卡决策: {}, 分数: {}", scorecardResult.getDesc(), scorecardScore);

        LoanApplication application = new LoanApplication();
        application.setApplicationId(IdUtil.generateId());
        application.setUserId(userId);
        application.setProductId(applyDTO.getProductId());
        application.setApplyAmount(applyAmount);
        application.setApplyTerm(applyTerm);
        application.setCreditScore(scorecardScore);
        application.setScorecardScore(scorecardScore);
        application.setDisbursementCardId(disbursementCardId);

        String decisionCode = mapScorecardResultToDecisionCode(scorecardResult);
        application.setScorecardDecision(decisionCode);

        switch (scorecardResult) {
            case AUTO_PASS:
                application.setApplicationStatus(ApplicationStatusEnum.APPROVED.getCode());
                log.info("✅ 自动通过");
                break;
            case MANUAL_REVIEW:
                application.setApplicationStatus(ApplicationStatusEnum.PENDING.getCode());
                log.info("⚠️ 转人工审核");
                break;
            case AUTO_REJECT:
                application.setApplicationStatus(ApplicationStatusEnum.REJECTED.getCode());
                log.info("❌ 自动拒绝");
                break;
        }

        application.setApplyTime(new Date());

        int rows = loanApplicationMapper.insert(application);
        if (rows != 1) {
            throw new BusinessException(500, "申请提交失败，请重试");
        }

        log.info("💾 贷款申请保存成功，申请ID: {}", application.getApplicationId());

        FeatureTransformationResultDTO transformationResult = FeatureTransformationResultDTO.success(
                userProfile,
                buildTransformationDetails(userProfile)
        );

        RiskDecisionLog riskLog = riskDecisionLogService.buildLogFromTransformation(
                application.getApplicationId(),
                userId,
                scorecardScore,
                decisionCode,
                transformationResult,
                null,
                null
        );
        riskDecisionLogService.logDecision(riskLog);

        creditScoreSyncService.syncScorecardToCreditScore(userId, scorecardScore, application.getApplicationId());

        String message;
        if (scorecardResult == ScorecardResultEnum.AUTO_PASS) {
            message = String.format("评分卡自动通过（得分：%d），申请已批准", scorecardScore);
        } else if (scorecardResult == ScorecardResultEnum.MANUAL_REVIEW) {
            message = String.format("申请提交成功（得分：%d），等待人工审核", scorecardScore);
        } else {
            message = String.format("评分卡评估未通过（得分：%d），申请已被拒绝", scorecardScore);
        }

        return Result.success(message);
    }

    @Override
    public Result<?> submitApplyWithBasicInfo(UserBasicInfoDTO basicInfo,  String userId) {
        log.info("📝 用户 {} 提交贷款申请（使用基础信息）: 产品={}, 金额={}, 期限={}",
                userId, basicInfo.getProductId(), basicInfo.getApplyAmount(), basicInfo.getApplyTerm());

        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || user.getKycStatus() == null || user.getKycStatus() != 1) {
            throw new BusinessException(403, "您尚未完成实名认证，请先进行身份认证后再申请贷款");
        }

        List<MonthlyRepayment> overdueRecords = monthlyRepaymentMapper.selectOverdueByUserId(userId);
        if (!overdueRecords.isEmpty()) {
            throw new BusinessException(400, "您有" + overdueRecords.size() + "笔逾期未还款记录，请先结清欠款后再申请");
        }

        LoanProduct product = loanProductService.validateProduct(basicInfo.getProductId());

        BigDecimal applyAmount = basicInfo.getApplyAmount();
        Integer applyTerm = basicInfo.getApplyTerm();
        if (applyAmount.compareTo(product.getMinAmount()) < 0 || applyAmount.compareTo(product.getMaxAmount()) > 0) {
            throw new BusinessException(400, "申请金额需在" + product.getMinAmount() + "-" + product.getMaxAmount() + "元之间");
        }
        if (applyTerm < product.getMinTerm() || applyTerm > product.getMaxTerm()) {
            throw new BusinessException(400, "申请期限需在" + product.getMinTerm() + "-" + product.getMaxTerm() + "月之间");
        }

        Long disbursementCardId = basicInfo.getDisbursementCardId();
        if (disbursementCardId != null) {
            UserBankCard bankCard = userBankCardMapper.selectById(disbursementCardId);
            if (bankCard == null) {
                throw new BusinessException(400, "指定的银行卡不存在");
            }
            if (!userId.equals(bankCard.getUserId())) {
                throw new BusinessException(400, "无权使用该银行卡作为放款账户");
            }
            if (!"ACTIVE".equals(bankCard.getBindStatus())) {
                throw new BusinessException(400, "该银行卡已解绑，请选择其他卡片");
            }
            log.info("✅ 用户指定放款银行卡: {}", bankCard.getAliasName());
        } else {
            log.info("ℹ️ 用户未指定放款银行卡，将使用默认卡");
        }

        List<String> validationErrors = dataValidationService.validateInputData(basicInfo);
        if (!validationErrors.isEmpty()) {
            throw new BusinessException(400, "数据验证失败: " + String.join(", ", validationErrors));
        }

        FeatureTransformationResultDTO transformationResult = featureTransformationService.transformToUserProfile(basicInfo);
        if (!transformationResult.getIsValid()) {
            throw new BusinessException(400, "特征转换失败: " + transformationResult.getErrorMessage());
        }

        UserProfileDTO userProfile = transformationResult.getUserProfile();

        log.info("🔍 开始调用评分卡模型计算分数...");
        Integer scorecardScore = scorecardService.calculateScoreOnly(userProfile);
        log.info("✅ 评分卡计算完成，得分: {}", scorecardScore);

        if (product.getAutoPassScore() == null || product.getManualReviewScore() == null) {
            throw new BusinessException(500, "产品评分卡配置不完整，请联系管理员");
        }

        ScorecardResultEnum scorecardResult = RiskControlUtil.evaluateScorecard(
                scorecardScore,
                product.getAutoPassScore(),
                product.getManualReviewScore()
        );

        log.info("🎯 评分卡决策: {}, 分数: {}", scorecardResult.getDesc(), scorecardScore);

        LoanApplication application = new LoanApplication();
        application.setApplicationId(IdUtil.generateId());
        application.setUserId(userId);
        application.setProductId(basicInfo.getProductId());
        application.setApplyAmount(applyAmount);
        application.setApplyTerm(applyTerm);
        application.setCreditScore(scorecardScore);
        application.setScorecardScore(scorecardScore);
        application.setDisbursementCardId(disbursementCardId);

        String decisionCode = mapScorecardResultToDecisionCode(scorecardResult);
        application.setScorecardDecision(decisionCode);

        switch (scorecardResult) {
            case AUTO_PASS:
                application.setApplicationStatus(ApplicationStatusEnum.APPROVED.getCode());
                log.info("✅ 自动通过");
                break;
            case MANUAL_REVIEW:
                application.setApplicationStatus(ApplicationStatusEnum.PENDING.getCode());
                log.info("⚠️ 转人工审核");
                break;
            case AUTO_REJECT:
                application.setApplicationStatus(ApplicationStatusEnum.REJECTED.getCode());
                log.info("❌ 自动拒绝");
                break;
        }

        application.setApplyTime(new Date());

        int rows = loanApplicationMapper.insert(application);
        if (rows != 1) {
            throw new BusinessException(500, "申请提交失败，请重试");
        }

        log.info("💾 贷款申请保存成功，申请ID: {}", application.getApplicationId());

        List<String> consistencyWarnings = dataValidationService.validateLogicalConsistency(basicInfo);

        RiskDecisionLog riskLog = riskDecisionLogService.buildLogFromTransformation(
                application.getApplicationId(),
                userId,
                scorecardScore,
                decisionCode,
                transformationResult,
                validationErrors,
                consistencyWarnings
        );
        riskDecisionLogService.logDecision(riskLog);

        creditScoreSyncService.syncScorecardToCreditScore(userId, scorecardScore, application.getApplicationId());

        String message;
        if (scorecardResult == ScorecardResultEnum.AUTO_PASS) {
            message = String.format("评分卡自动通过（得分：%d），申请已批准", scorecardScore);
        } else if (scorecardResult == ScorecardResultEnum.MANUAL_REVIEW) {
            message = String.format("申请提交成功（得分：%d），等待人工审核", scorecardScore);
        } else {
            message = String.format("评分卡评估未通过（得分：%d），申请已被拒绝", scorecardScore);
        }

        return Result.success(message);
    }

    @Override
    public Result<List<LoanApplicationVO>> queryApplyProgress(String userId) {
        List<LoanApplicationVO> applicationList = loanApplicationMapper.selectByUserId(userId);
        return Result.success(applicationList);
    }
    @Override
    public Result<LoanApplicationBasicVO> getApplicationBasic(String applicationId) {
        log.info("🔍 查询申请基本信息: {}", applicationId);

        LoanApplication application = loanApplicationMapper.selectByApplicationId(applicationId);
        if (application == null) {
            throw new BusinessException(404, "申请不存在");
        }

        LoanApplicationBasicVO basicVO = new LoanApplicationBasicVO();

        basicVO.setApplicationId(application.getApplicationId());
        basicVO.setUserId(application.getUserId());
        basicVO.setProductId(application.getProductId());
        basicVO.setApplyAmount(application.getApplyAmount());
        basicVO.setApplyTerm(application.getApplyTerm());
        basicVO.setScorecardScore(application.getScorecardScore());
        basicVO.setScorecardDecision(application.getScorecardDecision());
        basicVO.setApplicationStatus(application.getApplicationStatus());
        basicVO.setStatusDesc(application.getStatusDesc());
        basicVO.setApplyTime(application.getApplyTime());
        basicVO.setUpdateTime(application.getUpdateTime());
        basicVO.setDisbursementCardId(application.getDisbursementCardId());

        LoanProduct product = loanProductService.validateProduct(application.getProductId());
        if (product != null) {
            basicVO.setProductName(product.getProductName());
            basicVO.setInterestRate(product.getInterestRate());
            basicVO.setRepaymentMethod("等额本息");

            BigDecimal monthlyPayment = calculateMonthlyPayment(
                    application.getApplyAmount(),
                    product.getInterestRate(),
                    application.getApplyTerm()
            );
            basicVO.setMonthlyPayment(monthlyPayment);
        }

        if (application.getDisbursementCardId() != null) {
            UserBankCard bankCard = userBankCardMapper.selectById(application.getDisbursementCardId());
            if (bankCard != null) {
                basicVO.setCardBankName(bankCard.getAliasName());
                basicVO.setCardNumber(maskCardNumber(bankCard.getCardNumber()));
            }
        }

        return Result.success(basicVO);
    }

    @Override
    public Result<ApplicantIdentityVO> getApplicantIdentity(String applicationId) {
        log.info("🔍 查询申请人身份信息: {}", applicationId);

        LoanApplication application = loanApplicationMapper.selectByApplicationId(applicationId);
        if (application == null) {
            throw new BusinessException(404, "申请不存在");
        }

        String userId = application.getUserId();

        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }

        ApplicantIdentityVO identityVO = new ApplicantIdentityVO();
        UserIdentity userIdentity = userIdentityMapper.selectByUserId(userId);

        identityVO.setUserId(userId);
        identityVO.setUserPhone(maskPhone(user.getPhone()));
        identityVO.setUserRealName(userIdentity.getUserRealName());
        identityVO.setUserName(user.getRealName());
        identityVO.setKycStatus(user.getKycStatus());
        identityVO.setKycStatusDesc(getKycStatusDesc(user.getKycStatus()));
        identityVO.setRegisterTime(user.getCreateTime());
        identityVO.setLastLoginTime(user.getUpdateTime());


        if (userIdentity != null) {
            identityVO.setIdCardNumber(maskIdCard(userIdentity.getIdCardNumber()));
            identityVO.setVerifyStatus(userIdentity.getVerifyStatus());
            identityVO.setVerifyStatusDesc(getVerifyStatusDesc(userIdentity.getVerifyStatus()));
            identityVO.setIdCardFrontUrl(userIdentity.getIdCardFrontUrl());
            identityVO.setFaceCaptureUrl(userIdentity.getFaceCaptureUrl());
            identityVO.setVerifyTime(userIdentity.getUpdateTime());

                   } else {
            identityVO.setVerifyStatus(0);
            identityVO.setVerifyStatusDesc("未验证");
        }

        return Result.success(identityVO);
    }
    @Override
    public Result<ScorecardDetailVO> getScorecardDetail(String applicationId) {
        log.info("🔍 查询评分卡结果详情: {}", applicationId);

        LoanApplication application = loanApplicationMapper.selectByApplicationId(applicationId);
        if (application == null) {
            throw new BusinessException(404, "申请不存在");
        }

        RiskDecisionLog riskLog = riskDecisionLogService.getByApplicationId(applicationId);
        if (riskLog == null || riskLog.getFeatureDetails() == null) {
            throw new BusinessException(404, "评分卡详情数据不存在");
        }

        ScorecardDetailVO detailVO = new ScorecardDetailVO();

        detailVO.setTotalScore(application.getScorecardScore());
        detailVO.setDecision(application.getScorecardDecision());
        detailVO.setDecisionDesc(getDecisionDesc(application.getScorecardDecision()));
        detailVO.setScoreLevel(getScoreLevel(application.getScorecardScore()));

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.Map<String, Object> featureDetails = mapper.readValue(
                    riskLog.getFeatureDetails(),
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}
            );

            detailVO.setBaseScore(calculateBaseScore());

            // Coerce parsed JSON values to expected numeric types to avoid ClassCastException
            Object revolObj = featureDetails.get("revolvingUtil");
            BigDecimal revolVal = toBigDecimal(revolObj);
            detailVO.setRevolvingUtil(buildFeatureScore(
                    "revolvingUtil", "循环信用利用率",
                    revolVal,
                    getRevolvingUtilLevel(revolVal),
                    getRevolvingUtilDesc(revolVal)
            ));

            Object ageObj = featureDetails.get("age");
            Integer ageVal = toInteger(ageObj);
            detailVO.setAge(buildFeatureScore(
                    "age", "年龄",
                    ageVal,
                    getAgeLevel(ageVal),
                    getAgeDesc(ageVal)
            ));

            Object debtObj = featureDetails.get("debtRatio");
            BigDecimal debtVal = toBigDecimal(debtObj);
            detailVO.setDebtRatio(buildFeatureScore(
                    "debtRatio", "负债率",
                    debtVal,
                    getDebtRatioLevel(debtVal),
                    getDebtRatioDesc(debtVal)
            ));

            Object incomeObj = featureDetails.get("monthlyIncome");
            BigDecimal incomeVal = toBigDecimal(incomeObj);
            detailVO.setMonthlyIncome(buildFeatureScore(
                    "monthlyIncome", "月收入",
                    incomeVal,
                    getIncomeLevel(incomeVal),
                    getIncomeDesc(incomeVal)
            ));

            Object creditLinesObj = featureDetails.get("creditLines");
            Integer creditLinesVal = toInteger(creditLinesObj);
            detailVO.setCreditLines(buildFeatureScore(
                    "creditLines", "信贷账户数",
                    creditLinesVal,
                    getCreditLinesLevel(creditLinesVal),
                    getCreditLinesDesc(creditLinesVal)
            ));

            Object dependentsObj = featureDetails.get("dependents");
            Integer dependentsVal = toInteger(dependentsObj);
            detailVO.setDependents(buildFeatureScore(
                    "dependents", "家属数量",
                    dependentsVal,
                    getDependentsLevel(dependentsVal),
                    getDependentsDesc(dependentsVal)
            ));

        } catch (Exception e) {
            log.error("解析评分卡详情失败", e);
            throw new BusinessException(500, "评分卡详情解析失败");
        }

        return Result.success(detailVO);
    }

    private ScorecardDetailVO.FeatureScore buildFeatureScore(
            String name, String desc, Object value, String level, String description) {
        ScorecardDetailVO.FeatureScore score = new ScorecardDetailVO.FeatureScore();
        score.setFeatureName(name);
        score.setFeatureDesc(desc);
        if (value instanceof BigDecimal) {
            score.setFeatureValue((BigDecimal) value);
        } else if (value instanceof Integer) {
            score.setFeatureValue(new BigDecimal(value.toString()));
        }
        score.setLevel(level);
        score.setDescription(description);
        return score;
    }

    // Helper to coerce various numeric types parsed from JSON into BigDecimal
    private BigDecimal toBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        if (o instanceof Number) {
            // Use string constructor to preserve integer values accurately
            return new BigDecimal(o.toString());
        }
        if (o instanceof String) {
            try {
                return new BigDecimal((String) o);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    // Helper to coerce various numeric types parsed from JSON into Integer
    private Integer toInteger(Object o) {
        if (o == null) return null;
        if (o instanceof Integer) return (Integer) o;
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String) {
            try {
                return Integer.valueOf((String) o);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer calculateBaseScore() {
        return 791;
    }

    private String getDecisionDesc(String decision) {
        if (decision == null) {
            return "未知";
        }
        switch (decision) {
            case "AUTO_APPROVE":
                return "自动通过";
            case "MANUAL_REVIEW":
                return "人工审核";
            case "REJECT":
                return "拒绝";
            default:
                return "未知";
        }
    }

    private String getScoreLevel(Integer score) {
        if (score == null) {
            return "未知";
        }
        if (score >= 700) {
            return "优秀";
        } else if (score >= 650) {
            return "良好";
        } else if (score >= 600) {
            return "一般";
        } else {
            return "较差";
        }
    }

    private String getRevolvingUtilLevel(BigDecimal value) {
        if (value == null) return "未知";
        if (value.compareTo(new BigDecimal("0.2")) < 0) return "低";
        if (value.compareTo(new BigDecimal("0.5")) < 0) return "中";
        return "高";
    }

    private String getRevolvingUtilDesc(BigDecimal value) {
        if (value == null) return "数据缺失";
        return String.format("循环信用利用率为 %.2f%%", value.doubleValue() * 100);
    }

    private String getAgeLevel(Integer age) {
        if (age == null) return "未知";
        if (age < 35) return "年轻";
        if (age < 50) return "中年";
        return "年长";
    }

    private String getAgeDesc(Integer age) {
        if (age == null) return "数据缺失";
        return String.format("年龄 %d 岁", age);
    }

    private String getDebtRatioLevel(BigDecimal ratio) {
        if (ratio == null) return "未知";
        if (ratio.compareTo(new BigDecimal("0.3")) < 0) return "低";
        if (ratio.compareTo(new BigDecimal("0.6")) < 0) return "中";
        return "高";
    }

    private String getDebtRatioDesc(BigDecimal ratio) {
        if (ratio == null) return "数据缺失";
        return String.format("负债率为 %.2f%%", ratio.doubleValue() * 100);
    }

    private String getIncomeLevel(BigDecimal income) {
        if (income == null) return "未知";
        if (income.compareTo(new BigDecimal("5000")) < 0) return "低";
        if (income.compareTo(new BigDecimal("10000")) < 0) return "中";
        return "高";
    }

    private String getIncomeDesc(BigDecimal income) {
        if (income == null) return "数据缺失";
        return String.format("月收入 ¥%.2f", income.doubleValue());
    }

    private String getCreditLinesLevel(Integer lines) {
        if (lines == null) return "未知";
        if (lines < 5) return "少";
        if (lines < 10) return "中";
        return "多";
    }

    private String getCreditLinesDesc(Integer lines) {
        if (lines == null) return "数据缺失";
        return String.format("信贷账户数 %d 个", lines);
    }

    private String getDependentsLevel(Integer dependents) {
        if (dependents == null) return "未知";
        if (dependents <= 1) return "少";
        if (dependents <= 3) return "中";
        return "多";
    }

    private String getDependentsDesc(Integer dependents) {
        if (dependents == null) return "数据缺失";
        return String.format("需赡养家属 %d 人", dependents);
    }


    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private String maskIdCard(String idCard) {
        if (idCard == null || idCard.length() < 10) {
            return idCard;
        }
        return idCard.substring(0, 6) + "********" + idCard.substring(idCard.length() - 4);
    }

    private String getKycStatusDesc(Integer kycStatus) {
        if (kycStatus == null) {
            return "未认证";
        }
        switch (kycStatus) {
            case 0:
                return "未认证";
            case 1:
                return "已认证";
            default:
                return "未知";
        }
    }

    private String getVerifyStatusDesc(Integer verifyStatus) {
        if (verifyStatus == null) {
            return "未验证";
        }
        switch (verifyStatus) {
            case 0:
                return "未验证";
            case 1:
                return "已验证";
            default:
                return "未知";
        }
    }
    private UserProfileDTO buildUserProfileFromApplyDTO(LoanApplyDTO applyDTO) {
        UserProfileDTO userProfile = new UserProfileDTO();
        userProfile.setRevolvingUtil(applyDTO.getRevolvingUtil());
        userProfile.setAge(applyDTO.getAge());
        userProfile.setDebtRatio(applyDTO.getDebtRatio());
        userProfile.setMonthlyIncome(applyDTO.getMonthlyIncome());
        userProfile.setCreditLines(applyDTO.getCreditLines());
        userProfile.setDependents(applyDTO.getDependents());
        return userProfile;
    }

    private String mapScorecardResultToDecisionCode(ScorecardResultEnum result) {
        switch (result) {
            case AUTO_PASS:
                return "AUTO_APPROVE";
            case MANUAL_REVIEW:
                return "MANUAL_REVIEW";
            case AUTO_REJECT:
                return "REJECT";
            default:
                return "UNKNOWN";
        }
    }

    private java.util.Map<String, Object> buildTransformationDetails(UserProfileDTO profile) {
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("age", profile.getAge());
        details.put("dependents", profile.getDependents());
        details.put("monthlyIncome", profile.getMonthlyIncome());
        details.put("debtRatio", profile.getDebtRatio());
        details.put("revolvingUtil", profile.getRevolvingUtil());
        details.put("creditLines", profile.getCreditLines());
        return details;
    }
    private BigDecimal calculateMonthlyPayment(BigDecimal principal, BigDecimal annualRate, Integer termMonths) {
        if (principal == null || annualRate == null || termMonths == null || termMonths <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal monthlyRate = annualRate.divide(new BigDecimal("12"), 10, java.math.RoundingMode.HALF_UP);

        if (monthlyRate.compareTo(BigDecimal.ZERO) == 0) {
            return principal.divide(new BigDecimal(termMonths), 2, java.math.RoundingMode.HALF_UP);
        }

        BigDecimal factor = BigDecimal.ONE.add(monthlyRate).pow(termMonths);
        BigDecimal numerator = monthlyRate.multiply(factor);
        BigDecimal denominator = factor.subtract(BigDecimal.ONE);

        return principal.multiply(numerator).divide(denominator, 2, java.math.RoundingMode.HALF_UP);
    }

    private String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 8) {
            return cardNumber;
        }
        return cardNumber.substring(0, 4) + " **** **** " + cardNumber.substring(cardNumber.length() - 4);
    }
}
