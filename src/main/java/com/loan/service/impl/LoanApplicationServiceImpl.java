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

    /** 阶段 3：规则引擎接入申请链路（规则默认停用 → 无命中 → 审批行为与接入前一致） */
    @Resource
    private com.loan.rule.RiskRuleEngine riskRuleEngine;

    @Resource
    private com.loan.service.RiskRuleService riskRuleService;

    @Resource
    private com.loan.mapper.RiskRuleHitLogMapper riskRuleHitLogMapper;

    /** 阶段 C1：活体结果读取（第一层「信息校验先行」信号装配用） */
    @Resource
    private com.loan.mapper.UserFaceLogMapper userFaceLogMapper;

    /** 阶段 C5：设备指纹跨账号检索（第一层信号装配用） */
    @Resource
    private com.loan.mapper.UserMultiSourceDataMapper userMultiSourceDataMapper;

    /** 评分路由服务（第二层口径统一：建档 / 申请 / 重算三处共用同一实现） */
    @Resource
    private com.loan.service.ScorecardRoutingService scorecardRoutingService;

    /** 模型注册表（T3：用于算出"落选特征"—— 在多源宽表里但未进入当前默认模型的字段） */
    @Resource
    private com.loan.scorecard.ModelRegistry modelRegistry;

    /** 精细化定价服务（设计 §2：按评分区间匹配利率档位） */
    @Resource
    private PricingService pricingService;

    /** 业务指标（阶段一 1.5：把风控决策翻译成 Prometheus 指标） */
    @Resource
    private com.loan.metrics.RiskMetrics riskMetrics;

    /** 多源档案（供规则上下文使用非传统数据） */
    @Resource
    private com.loan.service.MultiSourceService multiSourceService;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private UserIdentityMapper userIdentityMapper;

    @Resource
    private UserCreditProfileMapper userCreditProfileMapper;

    /** 阶段四 4.3：幂等控制（防连点重复提交） */
    @Resource
    private com.loan.service.IdempotencyService idempotencyService;


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

        // 提前生成申请 ID：规则评估需要按申请单写入命中流水
        String applicationId = IdUtil.generateId();
        // 1.3：把申请单号写入 MDC —— 此后该请求的所有日志都会带 [applicationId=xxx]
        // 注意：TraceIdFilter 已在 finally 中 MDC.clear()，此处无需手动清理
        org.slf4j.MDC.put(com.loan.config.TraceIdFilter.APP_ID, applicationId);

        // ===== 阶段 3-a：L1 反欺诈层「即时拦截」=====
        // 设计文档 §2：校验不通过直接拒绝，**不进入后续加工与评分**（与 /risk/decision 的 L1 短路语义对齐）
        // 规则默认 enabled=0 → 无命中 → 不会短路，行为与接入前完全一致
        com.loan.rule.RiskRuleEngine.RuleOutcome l1Outcome =
                evaluateRiskRulesByLayer(applicationId, userId, "L1", null, applyAmount, userProfile);
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
        com.loan.scorecard.ProductThresholdResolver.ResolvedThresholds thresholds =
                productThresholdResolver.resolve(product);

        ScorecardResultEnum scorecardResult = RiskControlUtil.evaluateScorecard(
                scorecardScore,
                thresholds.autoPassScore(),
                thresholds.manualReviewScore()
        );

        log.info("🎯 评分卡决策: {}, 分数: {}", scorecardResult.getDesc(), scorecardScore);

        // ===== 阶段 3-b：L3 策略决策层「后置修正」（输入 = 分 + 标签，不重算信用分）=====
        // 优先级 REJECT > MANUAL > DOWNGRADE > FLAG；规则默认 enabled=0 → 无命中
        com.loan.rule.RiskRuleEngine.RuleOutcome ruleOutcome =
                evaluateRiskRulesByLayer(applicationId, userId, "L3", scorecardScore, applyAmount, userProfile);
        ScorecardResultEnum beforeRule = scorecardResult;
        scorecardResult = RuleDecisionSupport.mergeRuleDecision(scorecardResult, ruleOutcome.action());
        if (scorecardResult != beforeRule) {
            log.info("🛡 规则层覆盖决策: {} → {}（命中 {} 条, 动作={}）",
                    beforeRule.getDesc(), scorecardResult.getDesc(),
                    ruleOutcome.hits().size(), ruleOutcome.action());
        }

        // ===== S2：L1 层非 REJECT 动作参与决策归并 =====
        // 背景：L1 层原先只处理 REJECT（短路），其 MANUAL / DOWNGRADE / FLAG 命中被**直接丢弃**
        // → 设计 §8 要求的「规则命中存疑标签 / 操作行为异常 → 转人工」通路断开。
        // 处理：REJECT 仍然短路不评分；非 REJECT 动作在此累计到最终决策（与 L3 动作按同一优先级归并）。
        String l1Action = l1Outcome.action();
        if (l1Action != null && !"REJECT".equalsIgnoreCase(l1Action)) {
            ScorecardResultEnum beforeL1 = scorecardResult;
            scorecardResult = RuleDecisionSupport.mergeRuleDecision(scorecardResult, l1Action);
            if (scorecardResult != beforeL1) {
                log.info("🛡 L1 层动作参与归并: {} → {}（L1 命中动作={}，命中 {} 条）",
                        beforeL1.getDesc(), scorecardResult.getDesc(),
                        l1Action, l1Outcome.hits().size());
            }
        }

        // ===== P-025：规则降额（DOWNGRADE）闭环 =====
        // 决策（2026-10-13）：以**申请金额**为基数，按规则比例下调；
        // action_param 语义 = **保留比例**（如 "0.6" → 只放 60%）。
        // 说明：评分卡不含金额参数（信用风险 ≠ 额度政策），故降额只能以申请金额为基准。
        BigDecimal approvedAmount = null;   // null = 未降额（放款/合同回退 apply_amount）
        if ("DOWNGRADE".equalsIgnoreCase(ruleOutcome.action())) {
            // 4.2 拆分：纯函数已移到 RuleDecisionSupport
        BigDecimal ratio = RuleDecisionSupport.parseRetainRatio(ruleOutcome.actionParam());
            if (ratio != null && ratio.signum() > 0) {
                approvedAmount = applyAmount.multiply(ratio)
                        .setScale(2, java.math.RoundingMode.HALF_UP);
                log.warn("📉 规则降额：申请 {} → 批准 {}（保留比例 {}）",
                        applyAmount, approvedAmount, ratio);
            } else {
                log.warn("⚠️ DOWNGRADE 命中但 action_param 非有效保留比例（{}），按不降额处理",
                        ruleOutcome.actionParam());
            }
        }

        // ===== 精细化定价（设计 §2「最终决策与精细化定价」）=====
        // 按评分匹配利率档位（loan_pricing_tier，配置驱动、可在线维护）；
        // 未命中档位时回退产品自带利率，现有行为不变。
        PricingService.PricingOutcome pricing =
                pricingService.resolveRate(product.getProductId(), scorecardScore);

        // ===== 阶段 3-c：多源数据完备性强制（device + credit）=====
        // 不再"字段缺失即静默放行"：核心多源缺失时，把「自动通过」降为「人工审核」，
        // 由审核员判断属于"用户未授权/未同步多源"还是"真实的低风险客户"。
        // 只降级 AUTO_PASS；AUTO_REJECT 与 MANUAL_REVIEW 不受影响。
        List<String> missingMultiSources = missingRequiredMultiSources(userId);
        if (scorecardResult == ScorecardResultEnum.AUTO_PASS && !missingMultiSources.isEmpty()) {
            log.warn("⚠️ 多源数据不足（缺少 {}），自动通过降为转人工：申请={}",
                    missingMultiSources, applicationId);
            scorecardResult = ScorecardResultEnum.MANUAL_REVIEW;
        }

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

        String decisionCode = mapScorecardResultToDecisionCode(scorecardResult);
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
        // S4c：批准金额（规则降额后的最终放款额；未降额时为 null）
        basicVO.setApprovedAmount(application.getApprovedAmount());
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
                basicVO.setCardNumber(ScorecardDetailAssembler.maskCardNumber(bankCard.getCardNumber()));
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
        identityVO.setUserPhone(ScorecardDetailAssembler.maskPhone(user.getPhone()));
        identityVO.setUserRealName(userIdentity.getUserRealName());
        identityVO.setUserName(user.getRealName());
        identityVO.setKycStatus(user.getKycStatus());
        identityVO.setKycStatusDesc(ScorecardDetailAssembler.getKycStatusDesc(user.getKycStatus()));
        identityVO.setRegisterTime(user.getCreateTime());
        identityVO.setLastLoginTime(user.getUpdateTime());


        if (userIdentity != null) {
            identityVO.setIdCardNumber(ScorecardDetailAssembler.maskIdCard(userIdentity.getIdCardNumber()));
            identityVO.setVerifyStatus(userIdentity.getVerifyStatus());
            identityVO.setVerifyStatusDesc(ScorecardDetailAssembler.getVerifyStatusDesc(userIdentity.getVerifyStatus()));
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
        detailVO.setDecisionDesc(ScorecardDetailAssembler.getDecisionDesc(application.getScorecardDecision()));
        detailVO.setScoreLevel(ScorecardDetailAssembler.getScoreLevel(application.getScorecardScore()));

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.Map<String, Object> featureDetails = mapper.readValue(
                    riskLog.getFeatureDetails(),
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}
            );

            detailVO.setBaseScore(ScorecardDetailAssembler.calculateBaseScore());

            // Coerce parsed JSON values to expected numeric types to avoid ClassCastException
            Object revolObj = featureDetails.get("revolvingUtil");
            BigDecimal revolVal = ScorecardDetailAssembler.toBigDecimal(revolObj);
            detailVO.setRevolvingUtil(ScorecardDetailAssembler.buildFeatureScore(
                    "revolvingUtil", "循环信用利用率",
                    revolVal,
                    ScorecardDetailAssembler.getRevolvingUtilLevel(revolVal),
                    ScorecardDetailAssembler.getRevolvingUtilDesc(revolVal)
            ));

            Object ageObj = featureDetails.get("age");
            Integer ageVal = ScorecardDetailAssembler.toInteger(ageObj);
            detailVO.setAge(ScorecardDetailAssembler.buildFeatureScore(
                    "age", "年龄",
                    ageVal,
                    ScorecardDetailAssembler.getAgeLevel(ageVal),
                    ScorecardDetailAssembler.getAgeDesc(ageVal)
            ));

            Object debtObj = featureDetails.get("debtRatio");
            BigDecimal debtVal = ScorecardDetailAssembler.toBigDecimal(debtObj);
            detailVO.setDebtRatio(ScorecardDetailAssembler.buildFeatureScore(
                    "debtRatio", "负债率",
                    debtVal,
                    ScorecardDetailAssembler.getDebtRatioLevel(debtVal),
                    ScorecardDetailAssembler.getDebtRatioDesc(debtVal)
            ));

            Object incomeObj = featureDetails.get("monthlyIncome");
            BigDecimal incomeVal = ScorecardDetailAssembler.toBigDecimal(incomeObj);
            detailVO.setMonthlyIncome(ScorecardDetailAssembler.buildFeatureScore(
                    "monthlyIncome", "月收入",
                    incomeVal,
                    ScorecardDetailAssembler.getIncomeLevel(incomeVal),
                    ScorecardDetailAssembler.getIncomeDesc(incomeVal)
            ));

            Object creditLinesObj = featureDetails.get("creditLines");
            Integer creditLinesVal = ScorecardDetailAssembler.toInteger(creditLinesObj);
            detailVO.setCreditLines(ScorecardDetailAssembler.buildFeatureScore(
                    "creditLines", "信贷账户数",
                    creditLinesVal,
                    ScorecardDetailAssembler.getCreditLinesLevel(creditLinesVal),
                    ScorecardDetailAssembler.getCreditLinesDesc(creditLinesVal)
            ));

            Object dependentsObj = featureDetails.get("dependents");
            Integer dependentsVal = ScorecardDetailAssembler.toInteger(dependentsObj);
            detailVO.setDependents(ScorecardDetailAssembler.buildFeatureScore(
                    "dependents", "家属数量",
                    dependentsVal,
                    ScorecardDetailAssembler.getDependentsLevel(dependentsVal),
                    ScorecardDetailAssembler.getDependentsDesc(dependentsVal)
            ));

        } catch (Exception e) {
            log.error("解析评分卡详情失败", e);
            throw new BusinessException(500, "评分卡详情解析失败");
        }

        return Result.success(detailVO);
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


    // ==================== 阶段 3：规则层接入申请链路 ====================

    /**
     * 申请链路的分层规则评估（L1 反欺诈即时拦截 / L3 策略后置修正）
     *
     * 上下文 = 评分卡分数 + 申请金额 + 6 个基础特征 + **多源档案（非传统数据）**；
     * 命中结果写入 risk_rule_hit_log（影子规则也记录，shadow=1）。
     * **规则默认 enabled=0** → 未启用任何规则时不命中、不改变决策，审批行为与接入前一致。
     *
     * @param layer 只评估该层的规则（"L1" 拦截层 / "L3" 修正层）；传 null 表示不限制层
     * @param score L1 阶段传 null（设计 §2：先验真后算分）；规则条件里缺失字段按"不命中"处理
     */
    /**
     * 1.5：把「算不出来的信号」记为指标 —— 数据采集断了的早期预警。
     *
     * <p>每个 {@code xxx_available=0} 表示该维度数据缺失，此时规则侧会把申请转人工
     * （如 {@code L1-11} 定位缺失、{@code L1-14} 可信度不足）。
     * 该指标持续升高说明<b>上游采集故障</b>，需要立刻排查 —— 否则风控形同虚设。
     *
     * <p><b>只在 L1 层统计</b>：信号只服务第一层；若 L3 层也统计，同一笔申请会被计两次，
     * 指标数值翻倍失真。
     */
    private void recordUnavailableSignals(java.util.Map<String, Object> signals, String layer) {
        if (!"L1".equalsIgnoreCase(layer)) {
            return;
        }
        recordIfUnavailable(signals, "device_available", "device");
        recordIfUnavailable(signals, "liveness_available", "liveness");
        recordIfUnavailable(signals, "geo_available", "geo");
        recordIfUnavailable(signals, "behavior_available", "behavior");
    }

    private void recordIfUnavailable(java.util.Map<String, Object> signals,
                                     String flagKey, String signalName) {
        Object v = signals == null ? null : signals.get(flagKey);
        if (v instanceof Number n && n.intValue() == 0) {
            riskMetrics.signalUnavailable(signalName);
        }
    }

    private com.loan.rule.RiskRuleEngine.RuleOutcome evaluateRiskRulesByLayer(
            String applicationId, String userId, String layer, Integer score,
            BigDecimal applyAmount, UserProfileDTO userProfile) {
        try {
            java.util.Map<String, Object> context = new java.util.LinkedHashMap<>();
            context.put("score", score);
            context.put("applyAmount", applyAmount);
            if (userProfile != null) {
                context.put("age", userProfile.getAge());
                context.put("revolving_util", userProfile.getRevolvingUtil());
                context.put("debt_ratio", userProfile.getDebtRatio());
                context.put("monthly_income", userProfile.getMonthlyIncome());
                context.put("credit_lines", userProfile.getCreditLines());
                context.put("dependents", userProfile.getDependents());
            }
            // 非传统数据：多源档案（credit / operator / social / device / location / behavior）
            java.util.Map<String, Object> multiBlocks = null;
            try {
                // 2.2a 请求级缓存：本方法被 L1/L3 各调一次，不加缓存则多源档案要查两遍
                // （实测一次申请共 5 次 selectByUserId）
                com.loan.entity.vo.MultiSourceProfileVO profile = com.loan.util.RequestCache.get(
                        "multiProfile:" + userId,
                        () -> multiSourceService.getProfile(userId).getData());
                multiBlocks = profile == null ? null : profile.getDataBlocks();
                RuleDecisionSupport.flattenInto(context, multiBlocks);
            } catch (Exception e) {
                log.warn("⚠️ 规则上下文读取多源档案失败: {}", e.getMessage());
            }

            // ===== 第一层「信息校验先行」派生信号（纯计算，判读全部交给 L1 规则）=====
            // 契约见 finance_dev/信息校验先行开发文档.md §4：扁平标量 + available 标志，
            // 算不出的信号给哨兵值 -1 且仍放入上下文（避免"字段缺失=静默放行"）
            // 阶段 C5：同设备指纹关联的其它用户数（无指纹则保持 null → 哨兵值）
            Integer blacklistHitCnt = null;
            try {
                Object deviceBlock = multiBlocks == null ? null : multiBlocks.get("device");
                Object fp = deviceBlock instanceof java.util.Map<?, ?> m
                        ? m.get("device_fingerprint_hash") : null;
                if (fp != null) {
                    blacklistHitCnt = userMultiSourceDataMapper
                            .countOtherUsersByDeviceFingerprint(userId, String.valueOf(fp));
                }
            } catch (Exception e) {
                log.warn("⚠️ 设备指纹跨账号检索失败: {}", e.getMessage());
            }

            // 阶段 C1/C2/C5：活体（user_face_log）+ 地址（信用档案）+ 指纹跨账号计数
            // 2.2a：活体与信用档案走请求级缓存 —— L1/L3 各调一次本方法，否则各查两遍
            java.util.Map<String, Object> l1Signals = com.loan.rule.L1SignalAssembler
                    .assemble(multiBlocks,
                              com.loan.util.RequestCache.get("faceLog:" + userId,
                                      () -> userFaceLogMapper.selectLatestByUserId(userId)),
                              com.loan.util.RequestCache.get("creditProfile:" + userId,
                                      () -> userCreditProfileMapper.selectByUserId(userId)),
                              blacklistHitCnt);
            // 1.5：信号缺失埋点 —— 「数据采集断了 = 风控在裸奔」的早期预警（仅 L1 层统计）
            recordUnavailableSignals(l1Signals, layer);
            l1Signals.forEach(context::putIfAbsent);

            // ===== T3【特征工程层 §3.2 门禁】落选特征清单 =====
            // 设计 §3.2：宽表特征过 IV/PSI 门禁 → 入模(L2) / 落选(→ L3 规则修正)。
            // 现状：门禁只在离线训练侧执行，"落选"体现为"不在 model_config 的 features 里"。
            // 本处把「在多源宽表里、但未进入当前默认模型的字段」显式暴露给规则上下文，
            // 供人工在 Web 端配置 L3 规则（对应决策 D-T3 选项 (a)）。
            try {
                com.loan.scorecard.model.ScorecardModel defaultModel =
                        modelRegistry.get(modelRegistry.defaultKey());
                java.util.Set<String> inModel = new java.util.HashSet<>(
                        defaultModel == null ? java.util.List.of() : defaultModel.featureNames());
                java.util.List<String> skipped = new java.util.ArrayList<>();
                if (multiBlocks != null) {
                    multiBlocks.forEach((src, block) -> {
                        if (block instanceof java.util.Map<?, ?> m) {
                            m.keySet().forEach(k -> {
                                String f = String.valueOf(k);
                                if (!"data_version".equals(f) && !inModel.contains(f)) {
                                    skipped.add(f);
                                }
                            });
                        }
                    });
                }
                context.put("skipped_feature_count", skipped.size());
                context.put("skipped_features", String.join(",", skipped));
                if (!skipped.isEmpty()) {
                    log.info("🚪 落选特征（未入模，可供 L3 规则使用）: {}", skipped);
                }
            } catch (Exception e) {
                log.warn("⚠️ 落选特征计算失败（不影响主流程）: {}", e.getMessage());
            }

            // 2.2a：规则列表走请求级缓存 —— L1/L3 各调一次本方法，否则全表查两遍
            List<RiskRule> runnable = com.loan.util.RequestCache.get("riskRules:all",
                    () -> riskRuleService.list(null)).stream()
                    .filter(r -> Boolean.TRUE.equals(r.getEnabled()))
                    // 分层：L1 只跑拦截层、L3 只跑修正层（落实设计 §2 前置拦截 / §6 后置修正的层次语义）
                    .filter(r -> layer == null || layer.equalsIgnoreCase(r.getLayer()))
                    .sorted(java.util.Comparator.comparingInt(r -> r.getPriority() == null ? 999 : r.getPriority()))
                    .toList();

            com.loan.rule.RiskRuleEngine.RuleOutcome outcome = riskRuleEngine.evaluate(runnable, context);

            for (com.loan.rule.RiskRuleEngine.RuleHit hit : outcome.hits()) {
                // 1.5：规则命中计数（含影子模式）—— 用于判断规则是否该转正、哪条从未命中
                riskMetrics.ruleHit(hit.ruleCode(), hit.shadow());
                try {
                    RiskRuleHitLog hitLog = new RiskRuleHitLog();
                    hitLog.setApplicationId(applicationId);
                    hitLog.setUserId(userId);
                    hitLog.setRuleId(hit.ruleId());
                    hitLog.setRuleCode(hit.ruleCode());
                    hitLog.setRuleName(hit.name());
                    hitLog.setLayer(hit.layer());
                    hitLog.setAction(hit.action());
                    hitLog.setShadow(hit.shadow());
                    riskRuleHitLogMapper.insert(hitLog);
                } catch (Exception e) {
                    log.warn("⚠️ 规则命中流水写入失败: rule={}, reason={}", hit.ruleCode(), e.getMessage());
                }
            }
            log.info("🛡 规则评估: 启用 {} 条, 命中 {} 条, 合并动作={}",
                    runnable.size(), outcome.hits().size(), outcome.action());
            return outcome;
        } catch (Exception e) {
            // 规则层异常绝不影响主流程
            log.warn("⚠️ 规则评估异常，按无命中处理: {}", e.getMessage());
            return new com.loan.rule.RiskRuleEngine.RuleOutcome(
                    java.util.Collections.emptyList(), null, null, "规则评估异常");
        }
    }


    // ==================== L1 反欺诈即时拦截（设计文档 §2） ====================

    /** 核心多源数据源（强制完备性检查）—— 如需放宽/收紧，只改这一行 */
    private static final List<String> REQUIRED_MULTI_SOURCES = List.of("credit", "device");

    /**
     * 检查核心多源数据是否缺失（device + credit）
     *
     * 与「字段缺失 = 不命中 = 静默放行」的做法相反：这里把"缺数据"显式暴露出来，
     * 由调用方降级为人工审核，避免无数据用户被机器静默放过。
     *
     * @return 缺失的源名列表；空列表表示核心多源完备
     */
    private List<String> missingRequiredMultiSources(String userId) {
        try {
            com.loan.entity.vo.MultiSourceProfileVO profile = multiSourceService.getProfile(userId).getData();
            Map<String, Object> blocks = profile == null ? null : profile.getDataBlocks();
            if (blocks == null) {
                return REQUIRED_MULTI_SOURCES;
            }
            return REQUIRED_MULTI_SOURCES.stream()
                    .filter(src -> {
                        Object block = blocks.get(src);
                        return !(block instanceof Map<?, ?> m) || m.isEmpty();
                    })
                    .toList();
        } catch (Exception e) {
            // 取数异常按"缺失"处理：宁可转人工，也不静默放行
            log.warn("⚠️ 多源完备性检查异常，按缺失处理: {}", e.getMessage());
            return REQUIRED_MULTI_SOURCES;
        }
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
        application.setScorecardDecision(mapScorecardResultToDecisionCode(ScorecardResultEnum.AUTO_REJECT));
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
