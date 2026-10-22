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
    private LoanProductService loanProductService;

    @Resource
    private MonthlyRepaymentMapper monthlyRepaymentMapper;

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

    /** 阶段 3：规则引擎接入申请链路（规则默认停用 → 无命中 → 审批行为与接入前一致） */


    /** 阶段 C1：活体结果读取（第一层「信息校验先行」信号装配用） */

    /** 阶段 C5：设备指纹跨账号检索（第一层信号装配用） */

    /** 评分路由服务（第二层口径统一：建档 / 申请 / 重算三处共用同一实现） */
    @Resource
    private com.loan.service.ScorecardRoutingService scorecardRoutingService;

    /** 模型注册表（T3：用于算出"落选特征"—— 在多源宽表里但未进入当前默认模型的字段） */

    /** 业务指标（阶段一 1.5：把风控决策翻译成 Prometheus 指标） */
    @Resource
    private com.loan.metrics.RiskMetrics riskMetrics;

    /** 多源档案（供规则上下文使用非传统数据） */

    @Resource
    private SysUserMapper sysUserMapper;

    /** 阶段四 4.3：幂等控制（防连点重复提交） */
    @Resource
    private com.loan.service.IdempotencyService idempotencyService;

    /** 阶段四 4.2：分层规则评估（L1 拦截 / L3 修正）—— 依赖密集（9 个），已独立成类 */
    @Resource
    private RuleEvaluationService ruleEvaluationService;

    /** 阶段四 4.2：信用档案自动保存（提交申请时顺带写入） */
    @Resource
    private CreditProfileAutoSaveService creditProfileAutoSaveService;

    /** 阶段四 4.2：申请准入校验（KYC / 逾期 / 金额期限 / 银行卡 / 信用档案） */
    @Resource
    private ApplicationEligibilityChecker eligibilityChecker;

    /** 阶段四 4.2：决策链（阈值 → 评分决策 → L3 修正 → L1 归并 → 降额 → 定价 → 多源降级） */
    @Resource
    private RiskDecisionChainService decisionChainService;

    /** 阶段四 4.2：申请查询（只读职责，与提交流程分离） */
    @Resource
    private LoanApplicationQueryService queryService;


    /**
     * 阶段四 4.1：资源归属校验（RBAC 之外的第二个维度）。
     *
     * <p><b>为什么需要它</b>：`SecurityConfig` 只保证"这个用户登录了、且不是普通用户访问管理端"，
     * 但它管不了"这个 applicationId 是不是你的"。没有这层校验时，
     * 用户 A 传用户 B 的 applicationId 就能看到 B 的合同、还款计划、决策日志。
     *
     * <p>调用方：用户端接口（合同下载/状态、还款详情、决策日志）。
     * 管理端接口不需要 —— 管理员本就该能查全部。
     */
    @Override
    public boolean belongsTo(String applicationId, String userId) {
        if (applicationId == null || applicationId.isBlank() || userId == null || userId.isBlank()) {
            return false;
        }
        LoanApplication app = loanApplicationMapper.selectByApplicationId(applicationId);
        return app != null && userId.equals(app.getUserId());
    }

    @Override
    public Result<?> submitApply(LoanApplyDTO applyDTO, String userId) {
        // 阶段四 4.3：幂等检查（第 ② 层：Redis SETNX）
        // 同一 Idempotency-Key 在 5 分钟内重复提交 → 409；未带该头的老客户端放行（返回 null）
        String idemKey = idempotencyService.acquire("apply", userId);

        log.info("📝 用户 {} 提交贷款申请: 产品={}, 金额={}, 期限={}",
                userId, applyDTO.getProductId(), applyDTO.getApplyAmount(), applyDTO.getApplyTerm());

        // 阶段四 4.2 第 6 步：准入校验已移到 ApplicationEligibilityChecker
        // （KYC / 逾期 / 金额期限 / 银行卡 / 信用档案，8 个 throw 点类型与文案原样保留）
        ApplicationEligibilityChecker.Eligibility elig = eligibilityChecker.check(userId, applyDTO);
        LoanProduct product = elig.product();
        BigDecimal applyAmount = elig.applyAmount();
        Integer applyTerm = elig.applyTerm();
        Long disbursementCardId = elig.disbursementCardId();
        UserCreditProfile creditProfile = elig.creditProfile();
        UserProfileDTO userProfile = elig.userProfile();

        // 提前生成申请 ID：规则评估需要按申请单写入命中流水
        String applicationId = IdUtil.generateId();
        // 1.3：把申请单号写入 MDC —— 此后该请求的所有日志都会带 [applicationId=xxx]
        // 注意：TraceIdFilter 已在 finally 中 MDC.clear()，此处无需手动清理
        org.slf4j.MDC.put(com.loan.config.TraceIdFilter.APP_ID, applicationId);

        // ===== 阶段 3-a：L1 反欺诈层「即时拦截」=====
        // 设计文档 §2：校验不通过直接拒绝，**不进入后续加工与评分**（与 /risk/decision 的 L1 短路语义对齐）
        // 规则默认 enabled=0 → 无命中 → 不会短路，行为与接入前完全一致
        com.loan.rule.RiskRuleEngine.RuleOutcome l1Outcome =
                ruleEvaluationService.evaluate(applicationId, userId, "L1", null, applyAmount, userProfile);
        if (RuleDecisionSupport.isRejectAction(l1Outcome.action())) {
            log.warn("🚫 L1 反欺诈即时拦截，跳过评分卡：申请={}，命中 {} 条",
                    applicationId, l1Outcome.hits().size());
            return persistL1RejectedApplication(applicationId, userId, applyDTO, applyAmount,
                    applyTerm, disbursementCardId, creditProfile, l1Outcome);
        }

        log.info("🔍 开始评分（优先多源多模型，多源不足时回落单模型）...");
        ScoreOutcome scoreOutcome = calculateScorePreferMultiSource(userId, userProfile);
        Integer scorecardScore = scoreOutcome.score();
        log.info("✅ 评分完成，得分: {}，模型: {}，来源: {}，缺失特征: {}/{}",
                scorecardScore, scoreOutcome.modelKey(), scoreOutcome.source(),
                scoreOutcome.missingFeatures(), scoreOutcome.requiredFeatures());

        // 阶段 2：产品阈值解析（产品配置优先；未配置时回退全局默认阈值）
        // 阶段四 4.2 第 6 步：决策链已移到 RiskDecisionChainService
        // （阈值解析 → 评分决策 → L3 修正 → L1 归并 → 规则降额 → 精细化定价 → 多源降级）
        RiskDecisionChainService.DecisionResult decision = decisionChainService.decide(
                applicationId, userId, product, scorecardScore, applyAmount, userProfile, l1Outcome);
        com.loan.scorecard.ProductThresholdResolver.ResolvedThresholds thresholds = decision.thresholds();
        ScorecardResultEnum scorecardResult = decision.decision();
        BigDecimal approvedAmount = decision.approvedAmount();
        PricingService.PricingOutcome pricing = decision.pricing();

        LoanApplication application = new LoanApplication();
        application.setApplicationId(applicationId);
        application.setUserId(userId);
        application.setProductId(applyDTO.getProductId());
        application.setApplyAmount(applyAmount);
        application.setApprovedAmount(approvedAmount);   // P-025：null = 未降额
        application.setAppliedRate(pricing.annualRate()); // 精细化定价：null = 未定价
        application.setPricingTier(pricing.tierLabel());
        // 阶段四 4.3 第 ③ 层：把幂等键落库，与 user_id 组成唯一索引 uk_user_idem 兜底
        // （Redis 不可用/被绕过时，这里仍能挡住同一用户的重复提交；null 不参与唯一约束）
        application.setIdempotencyKey(idemKey);
        application.setApplyTerm(applyTerm);
        application.setCreditScore(scorecardScore);
        application.setScorecardScore(scorecardScore);
        application.setDisbursementCardId(disbursementCardId);

        String decisionCode = ScorecardDetailAssembler.mapScorecardResultToDecisionCode(scorecardResult);
        application.setScorecardDecision(decisionCode);

        // ===== 1.5 业务指标埋点 =====
        // 位置很关键：此处「规则归并 + 降额 + 定价 + 多源完备性」均已完成，
        // 是「决策最终确定」的那一刻。若前移到评分之后，统计会把 AUTO_PASS 多算、
        // REJECT 漏算（数字看起来正常，实际是错的）。
        riskMetrics.decision(decisionCode);
        if (approvedAmount != null && approvedAmount.compareTo(applyAmount) < 0) {
            riskMetrics.downgrade();          // 规则降额实际生效
        }
        if (pricing != null && pricing.matched()) {
            riskMetrics.pricingTier(pricing.tierLabel());
        }
        // 评分分布与特征缺失（用于漂移检测 / 模型质量评估）
        if (scorecardScore != null && scoreOutcome != null) {
            riskMetrics.score(scoreOutcome.modelKey(), scorecardScore);
            if (scoreOutcome.missingFeatures() != null) {
                riskMetrics.missingFeatures(scoreOutcome.modelKey(), scoreOutcome.missingFeatures());
            }
        }

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
                ScorecardDetailAssembler.buildTransformationDetails(userProfile)
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
        // ===== P-044 修复：补「特征快照」 =====
        // 此前 feature_snapshot **从未被赋值** → 恒为 NULL，导致：
        //   ① 审批详情页「特征贡献分层」卡片拿不到数据；
        //   ② approval-summary 的 featureSnapshot 为空；
        //   ③ S1 导出「特征 + Y 标签」样本时无特征可导。
        // 此处落「实际装配的特征」（含多源，与评分卡入参一致）。
        try {
            riskLog.setFeatureSnapshot(new ObjectMapper().writeValueAsString(
                    featureTransformationService.buildMultiSourceFeatures(userId)));
        } catch (Exception e) {
            log.warn("⚠️ 特征快照落库失败（不影响主流程）: {}", e.getMessage());
        }
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

        String decisionCode = ScorecardDetailAssembler.mapScorecardResultToDecisionCode(scorecardResult);
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
        creditProfileAutoSaveService.saveOrUpdate(userId, basicInfo, userProfile, scorecardScore);

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
        // 【口径统一 · P-030】委托给 ScorecardRoutingService —— 与建档链路共用同一实现。
        // 此前本方法是 ScorecardRoutingServiceImpl.score() 的逐行复制版，任何策略调整
        // （如"多源覆盖不足回落 v3"的判据）都必须两处同改，否则档案分与申请分再次分裂。
        com.loan.service.ScorecardRoutingService.ScoreOutcome routed =
                scorecardRoutingService.score(userId, userProfile);
        // 转换为本链路的内嵌 ScoreOutcome（字段一一对应，仅为兼容既有调用点）
        return new ScoreOutcome(routed.score(), routed.modelKey(), routed.modelVersion(),
                routed.missingFeatures(), routed.requiredFeatures(), routed.source());
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

    @Override
    public Result<List<LoanApplicationVO>> queryApplyProgress(String userId) {
        // 阶段四 4.2 第 5 步：查询逻辑已移到 LoanApplicationQueryService（只读职责与提交流程分离）
        return queryService.queryApplyProgress(userId);
    }

    @Override
    public Result<LoanApplicationBasicVO> getApplicationBasic(String applicationId) {
        return queryService.getApplicationBasic(applicationId);
    }

    @Override
    public Result<ApplicantIdentityVO> getApplicantIdentity(String applicationId) {
        return queryService.getApplicantIdentity(applicationId);
    }

    @Override
    public Result<ScorecardDetailVO> getScorecardDetail(String applicationId) {
        return queryService.getScorecardDetail(applicationId);
    }






    /**
     * L1 反欺诈即时拦截落档
     *
     * 设计文档 §2：第一层校验不通过**直接拒绝，不进入后续加工与评分**。
     * 与全局决策入口语义对齐（RiskDecisionServiceImpl 同样在评分前拦截 L1）：
     * - **不调用评分卡** → `scorecard_score` 留空，表示"本次未评分"
     * - `credit_score` 为 NOT NULL → 沿用信用档案里的分数
     * - 不生成合同草稿、不同步信用分
     * - 规则命中明细已写入 `risk_rule_hit_log`，足以追溯
     *   （不写 `risk_decision_log`：该表语义是"评分卡决策留痕"，此处无评分卡决策）
     */
    private Result<?> persistL1RejectedApplication(
            String applicationId, String userId, LoanApplyDTO applyDTO, BigDecimal applyAmount,
            Integer applyTerm, Long disbursementCardId, UserCreditProfile creditProfile,
            com.loan.rule.RiskRuleEngine.RuleOutcome l1Outcome) {

        LoanApplication application = new LoanApplication();
        application.setApplicationId(applicationId);
        application.setUserId(userId);
        application.setProductId(applyDTO.getProductId());
        application.setApplyAmount(applyAmount);
        application.setApplyTerm(applyTerm);
        application.setCreditScore(creditProfile.getCreditScore());   // NOT NULL：沿用档案分
        application.setScorecardScore(null);                           // L1 已拦截，未进入评分
        application.setDisbursementCardId(disbursementCardId);
        application.setScorecardDecision(ScorecardDetailAssembler.mapScorecardResultToDecisionCode(ScorecardResultEnum.AUTO_REJECT));
        application.setApplicationStatus(ApplicationStatusEnum.REJECTED.getCode());
        application.setApplyTime(new Date());
        loanApplicationMapper.insert(application);
        log.info("💾 L1 拦截申请已留档（未评分），申请ID: {}", applicationId);

        String ruleNames = l1Outcome.hits().stream()
                .map(com.loan.rule.RiskRuleEngine.RuleHit::name)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(java.util.stream.Collectors.joining("、"));
        return Result.error(403, "很抱歉，您的申请未通过反欺诈校验"
                + (ruleNames.isBlank() ? "" : "（" + ruleNames + "）") + "，已被即时拦截");
    }
}
