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
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import java.util.Map;

@Service
public class LoanApplicationServiceImpl implements LoanApplicationService {

    private static final Logger log = LoggerFactory.getLogger(LoanApplicationServiceImpl.class);

    @Resource
    private LoanApplicationMapper loanApplicationMapper;

    /** 多模型评分（v3/v4/v4-fusion，支持多源特征），用于让多源数据进入申请决策 */
    @Resource
    private MultiModelScorecardService multiModelScorecardService;

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

    /** 合同服务：自动通过后生成合同草稿（与人工审批一致） */
    @Resource
    private ContractService contractService;

    /** 阶段 2：产品级阈值解析（产品配置优先，缺失时回退全局默认） */
    @Resource
    private com.loan.scorecard.ProductThresholdResolver productThresholdResolver;

    @Resource
    private CreditScoreSyncService creditScoreSyncService;

    @Resource
    private UserBankCardMapper userBankCardMapper;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private UserIdentityMapper userIdentityMapper;

    @Resource
    private UserCreditProfileMapper userCreditProfileMapper;


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

        // 从信用档案读取个人特征数据（无需重复填写）
        UserCreditProfile creditProfile = userCreditProfileMapper.selectByUserId(userId);
        if (creditProfile == null || creditProfile.getCreditScore() == null) {
            throw new BusinessException(400, "请先创建信用档案，完善个人信息后再申请贷款");
        }

        UserProfileDTO userProfile = buildUserProfileFromProfile(creditProfile);

        log.info("🔍 开始评分（优先多源多模型，多源不足时回落单模型）...");
        ScoreOutcome scoreOutcome = calculateScorePreferMultiSource(userId, userProfile);
        Integer scorecardScore = scoreOutcome.score();
        log.info("✅ 评分完成，得分: {}，模型: {}，来源: {}，缺失特征: {}/{}",
                scorecardScore, scoreOutcome.modelKey(), scoreOutcome.source(),
                scoreOutcome.missingFeatures(), scoreOutcome.requiredFeatures());

        // 阶段 2：产品阈值解析（产品配置优先；未配置时回退全局默认阈值）
        com.loan.scorecard.ProductThresholdResolver.ResolvedThresholds thresholds =
                productThresholdResolver.resolve(product);

        ScorecardResultEnum scorecardResult = RiskControlUtil.evaluateScorecard(
                scorecardScore,
                thresholds.autoPassScore(),
                thresholds.manualReviewScore()
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
        // 阶段 2：记录本次生效的产品阈值与来源（阈值分化可追溯）
        riskLog.setProductId(product.getProductId());
        riskLog.setAppliedAutoPass(thresholds.autoPassScore());
        riskLog.setAppliedManualReview(thresholds.manualReviewScore());
        riskLog.setThresholdSource(thresholds.source());
        // 记录实际使用的评分模型（v3 单模型 / v4 多源多模型），便于分数追溯
        riskLog.setModelVersion(scoreOutcome.modelKey());
        riskDecisionLogService.logDecision(riskLog);

        creditScoreSyncService.syncScorecardToCreditScore(userId, scorecardScore, application.getApplicationId());

        // 自动通过：与人工审批保持一致，自动生成合同草稿（失败不阻断申请）
        if (scorecardResult == ScorecardResultEnum.AUTO_PASS) {
            generateDraftContractQuietly(application.getApplicationId());
        }

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

        log.info("🔍 开始评分（优先多源多模型，多源不足时回落单模型）...");
        ScoreOutcome scoreOutcome = calculateScorePreferMultiSource(userId, userProfile);
        Integer scorecardScore = scoreOutcome.score();
        log.info("✅ 评分完成，得分: {}，模型: {}，来源: {}，缺失特征: {}/{}",
                scorecardScore, scoreOutcome.modelKey(), scoreOutcome.source(),
                scoreOutcome.missingFeatures(), scoreOutcome.requiredFeatures());

        // 阶段 2：产品阈值解析（产品配置优先；未配置时回退全局默认阈值）
        com.loan.scorecard.ProductThresholdResolver.ResolvedThresholds thresholds =
                productThresholdResolver.resolve(product);

        ScorecardResultEnum scorecardResult = RiskControlUtil.evaluateScorecard(
                scorecardScore,
                thresholds.autoPassScore(),
                thresholds.manualReviewScore()
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
        // 阶段 2：记录本次生效的产品阈值与来源（阈值分化可追溯）
        riskLog.setProductId(application.getProductId());
        riskLog.setAppliedAutoPass(thresholds.autoPassScore());
        riskLog.setAppliedManualReview(thresholds.manualReviewScore());
        riskLog.setThresholdSource(thresholds.source());
        // 记录实际使用的评分模型（v3 单模型 / v4 多源多模型），便于分数追溯
        riskLog.setModelVersion(scoreOutcome.modelKey());
        riskDecisionLogService.logDecision(riskLog);

        creditScoreSyncService.syncScorecardToCreditScore(userId, scorecardScore, application.getApplicationId());

        // 自动保存/更新信用档案（下次申请无需重复填写）
        autoSaveCreditProfile(userId, basicInfo, userProfile, scorecardScore);

        // 自动通过：与人工审批保持一致，自动生成合同草稿（失败不阻断申请）
        if (scorecardResult == ScorecardResultEnum.AUTO_PASS) {
            generateDraftContractQuietly(application.getApplicationId());
        }

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

    /**
     * 生成合同草稿（自动通过路径）
     * 说明：合同生成失败不影响申请结果——用户进入签约页时后端会兜底补生成。
     */
    private void generateDraftContractQuietly(String applicationId) {
        try {
            contractService.generateDraftContract(applicationId);
            log.info("📄 合同草稿已生成: {}", applicationId);
        } catch (Exception e) {
            log.warn("⚠️ 合同草稿生成失败（不影响申请，签约时会重试）: {} - {}", applicationId, e.getMessage());
        }
    }

    /**
     * 评分（优先「多源特征 + 多模型」；多源覆盖不足时回落单模型）
     *
     * 目的：让已同步的多源数据（征信/运营商/设备）真正进入申请决策。
     * 保护：未授权/未同步多源的用户仍走原有单模型 v3，行为与接入前一致。
     */
    private ScoreOutcome calculateScorePreferMultiSource(String userId, UserProfileDTO userProfile) {
        try {
            Map<String, Object> features = featureTransformationService.buildMultiSourceFeatures(userId);
            if (features != null && !features.isEmpty()) {
                // 基础特征以档案口径为准（缺失时补齐，避免与单模型口径不一致）
                fillIfAbsent(features, "revolving_util", userProfile.getRevolvingUtil());
                fillIfAbsent(features, "age", userProfile.getAge());
                fillIfAbsent(features, "debt_ratio", userProfile.getDebtRatio());
                fillIfAbsent(features, "monthly_income", userProfile.getMonthlyIncome());
                fillIfAbsent(features, "credit_lines", userProfile.getCreditLines());
                fillIfAbsent(features, "dependents", userProfile.getDependents());

                com.loan.entity.dto.MultiModelScoreRequest request = new com.loan.entity.dto.MultiModelScoreRequest();
                request.setFeatures(features);
                request.setUserId(userId);

                com.loan.entity.vo.MultiModelScoreResultVO result = multiModelScorecardService.calculate(request);
                if (result != null && result.getTotalScore() != null) {
                    int required = result.getRequiredFeatureCount() == null ? 0 : result.getRequiredFeatureCount();
                    int missing = result.getMissingFeatures() == null ? 0 : result.getMissingFeatures().size();
                    // 多源覆盖率不足一半时回落单模型，避免"缺特征导致分数漂移"
                    if (required == 0 || missing * 2 <= required) {
                        log.info("✅ 多源多模型评分: model={} score={} 缺失特征={}/{}",
                                result.getModelKey(), result.getTotalScore(), missing, required);
                        return new ScoreOutcome(result.getTotalScore(), result.getModelKey(),
                                result.getModelVersion(), missing, required, "MULTI");
                    }
                    log.warn("⚠️ 多源特征覆盖不足（缺失 {}/{}），回落单模型评分", missing, required);
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ 多源多模型评分异常，回落单模型: {}", e.getMessage());
        }

        Integer singleScore = scorecardService.calculateScoreOnly(userProfile);
        log.info("✅ 单模型评分（回落）: score={}", singleScore);
        return new ScoreOutcome(singleScore, "v3", null, null, 6, "SINGLE");
    }

    /** 仅当目标特征缺失时补齐（保持多源链路给出的值优先） */
    private void fillIfAbsent(Map<String, Object> features, String key, Object value) {
        if (features.get(key) == null && value != null) {
            features.put(key, value);
        }
    }

    /** 评分结果及来源（用于日志与决策留痕追溯） */
    private record ScoreOutcome(Integer score, String modelKey, String modelVersion,
                                Integer missingFeatures, Integer requiredFeatures, String source) {
    }

    /**
     * 自动保存/更新信用档案
     */
    private void autoSaveCreditProfile(String userId, UserBasicInfoDTO basicInfo,
                                        UserProfileDTO userProfile, Integer scorecardScore) {
        try {
            UserCreditProfile existing = userCreditProfileMapper.selectByUserId(userId);
            boolean isNew = (existing == null);

            if (isNew) {
                existing = new UserCreditProfile();
                existing.setUserId(userId);
                existing.setCreateTime(new Date());
            }

            // 个人信息
            UserBasicInfoDTO.PersonalInfo p = basicInfo.getPersonalInfo();
            if (p != null) {
                existing.setName(p.getName());
                existing.setIdCard(p.getIdCard());
                existing.setPhone(p.getPhone());
                existing.setEmail(p.getEmail());
                existing.setGender(p.getGender());
                existing.setEducation(p.getEducation());
                existing.setOccupation(p.getOccupation());
                if (p.getBirthDate() != null) {
                    existing.setBirthDate(java.sql.Date.valueOf(p.getBirthDate()));
                }
            }

            // JSON 序列化（注册 JavaTimeModule 支持 LocalDate）
            try {
                ObjectMapper om = new ObjectMapper()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                        .registerModule(new JavaTimeModule());
                if (basicInfo.getFamilyInfo() != null) {
                    existing.setFamilyInfo(om.writeValueAsString(basicInfo.getFamilyInfo()));
                }
                if (basicInfo.getFinancialInfo() != null) {
                    existing.setFinancialInfo(om.writeValueAsString(basicInfo.getFinancialInfo()));
                }
                if (basicInfo.getEmploymentInfo() != null) {
                    existing.setEmploymentInfo(om.writeValueAsString(basicInfo.getEmploymentInfo()));
                }
            } catch (Exception e) {
                log.warn("序列化档案 JSON 失败: {}", e.getMessage());
            }

            // 评分特征
            existing.setAge(userProfile.getAge());
            existing.setDebtRatio(userProfile.getDebtRatio());
            existing.setMonthlyIncome(userProfile.getMonthlyIncome());
            existing.setCreditLines(userProfile.getCreditLines());
            existing.setDependents(userProfile.getDependents());
            existing.setRevolvingUtil(userProfile.getRevolvingUtil());
            existing.setCreditScore(scorecardScore);
            existing.setUpdateTime(new Date());

            if (isNew) {
                userCreditProfileMapper.insert(existing);
            } else {
                userCreditProfileMapper.update(existing);
            }

            log.info("✅ 信用档案已自动{}", isNew ? "创建" : "更新");
        } catch (Exception e) {
            log.warn("⚠️ 自动保存信用档案失败（不影响申请）: {}", e.getMessage());
        }
    }

    @Override
    public Result<List<LoanApplicationVO>> queryApplyProgress(String userId) {
        List<LoanApplicationVO> applicationList = loanApplicationMapper.selectByUserId(userId);
        // 兜底：已通过但合同未生成的申请（历史数据 / 自动通过早期版本），在用户查看进度时自动补生成
        if (applicationList != null) {
            for (LoanApplicationVO vo : applicationList) {
                repairMissingContract(vo);
            }
        }
        return Result.success(applicationList);
    }

    /**
     * 兜底修复：申请已通过但合同草稿缺失时补生成，并回填 VO
     *
     * 背景：App 进度页仅在 contractPath 非空且 contractStatus=1 时显示「签署合同」按钮，
     *       合同缺失会导致用户看不到按钮、无法签约，流程中断。
     * 该操作幂等：已有合同则直接返回。
     */
    private void repairMissingContract(LoanApplicationVO vo) {
        try {
            if (vo.getContractPath() != null && !vo.getContractPath().isEmpty()) {
                return;
            }
            if (!ApplicationStatusEnum.APPROVED.getCode().equals(vo.getApplicationStatus())) {
                return;
            }
            Result<String> draft = contractService.generateDraftContract(vo.getApplicationId());
            if (draft != null && Integer.valueOf(200).equals(draft.getCode())) {
                LoanApplication latest = loanApplicationMapper.selectByApplicationId(vo.getApplicationId());
                if (latest != null) {
                    vo.setContractPath(latest.getContractPath());
                    vo.setContractStatus(latest.getContractStatus());
                }
                log.info("🔧 已补生成缺失的合同草稿: {}", vo.getApplicationId());
            }
        } catch (Exception e) {
            log.warn("⚠️ 补生成合同失败（不影响进度查询）: {} - {}", vo.getApplicationId(), e.getMessage());
        }
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
    /**
     * 从信用档案构建评分所需的 UserProfileDTO
     */
    private UserProfileDTO buildUserProfileFromProfile(UserCreditProfile profile) {
        UserProfileDTO userProfile = new UserProfileDTO();
        userProfile.setRevolvingUtil(profile.getRevolvingUtil() != null ? profile.getRevolvingUtil() : BigDecimal.ZERO);
        userProfile.setAge(profile.getAge() != null ? profile.getAge() : 18);
        userProfile.setDebtRatio(profile.getDebtRatio() != null ? profile.getDebtRatio() : BigDecimal.ZERO);
        userProfile.setMonthlyIncome(profile.getMonthlyIncome());
        userProfile.setCreditLines(profile.getCreditLines() != null ? profile.getCreditLines() : 0);
        userProfile.setDependents(profile.getDependents() != null ? profile.getDependents() : 0);
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
