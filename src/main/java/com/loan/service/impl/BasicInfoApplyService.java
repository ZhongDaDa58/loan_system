package com.loan.service.impl;

import com.loan.entity.LoanApplication;
import com.loan.entity.RiskDecisionLog;
import com.loan.entity.dto.FeatureTransformationResultDTO;
import com.loan.entity.dto.UserBasicInfoDTO;
import com.loan.entity.dto.UserProfileDTO;
import com.loan.entity.enums.ApplicationStatusEnum;
import com.loan.entity.enums.ScorecardResultEnum;
import com.loan.entity.vo.Result;
import com.loan.exception.BusinessException;
import com.loan.mapper.LoanApplicationMapper;
import com.loan.scorecard.ProductThresholdResolver;
import com.loan.service.CreditScoreSyncService;
import com.loan.service.DataValidationService;
import com.loan.service.FeatureTransformationService;
import com.loan.service.RiskDecisionLogService;
import com.loan.service.ScorecardRoutingService;
import com.loan.util.IdUtil;
import com.loan.util.RiskControlUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 「填写完整信息」链路的申请提交（阶段四 4.2 第 7 步）
 *
 * <p><b>从哪来</b>：{@code LoanApplicationServiceImpl#submitApplyWithBasicInfo} 的主体
 * （约 120 行），<b>纯搬移，逻辑零变更</b>。
 *
 * <p><b>与 {@code submitApply}（信用档案链路）的区别</b> —— 这个区别很关键：
 * <table border="1">
 *   <tr><th></th><th>submitApply</th><th>submitApplyWithBasicInfo</th></tr>
 *   <tr><td>特征来源</td><td>信用档案（一次填写、后续复用）</td><td>用户本次填写的 UserBasicInfoDTO</td></tr>
 *   <tr><td>数据校验</td><td>无</td><td><b>有</b>（validateInputData + validateLogicalConsistency）</td></tr>
 *   <tr><td>特征转换</td><td>无（档案直接映射）</td><td><b>有</b>（transformToUserProfile）</td></tr>
 *   <tr><td>L1/L3 规则评估</td><td><b>有</b>（分层规则 + 降额 + 定价）</td><td><b>无</b>（只有分数阈值决策）</td></tr>
 *   <tr><td>档案回写</td><td>无</td><td><b>有</b>（本次填写的信息存进档案）</td></tr>
 * </table>
 *
 * <p><b>⚠️ 为什么这条链路没有 L1/L3 规则</b>：它是早期链路，规则层只接入了信用档案链路。
 * 这是<b>已知的功能差异</b>，不是本次拆分引入的。如果将来要补齐，
 * 应在本类里插入 {@code RuleEvaluationService} 的调用，而不是搬回主类。
 *
 * <p><b>为什么不并成一条链路</b>：两条链路的差异是<b>语义上的</b>
 * （"用已有档案"vs"这次填写的信息"），并起来会变成一堆 if 分支。
 * 拆成两个类、各自清晰，比强行统一更好。
 */
@Service
public class BasicInfoApplyService {

    private static final Logger log = LoggerFactory.getLogger(BasicInfoApplyService.class);

    @Resource
    private ApplicationEligibilityChecker eligibilityChecker;

    @Resource
    private DataValidationService dataValidationService;

    @Resource
    private FeatureTransformationService featureTransformationService;

    @Resource
    private ScorecardRoutingService scorecardRoutingService;

    @Resource
    private ProductThresholdResolver productThresholdResolver;

    @Resource
    private LoanApplicationMapper loanApplicationMapper;

    @Resource
    private RiskDecisionLogService riskDecisionLogService;

    @Resource
    private CreditScoreSyncService creditScoreSyncService;

    @Resource
    private CreditProfileAutoSaveService creditProfileAutoSaveService;

    @Resource
    private ContractDraftService contractDraftService;

    public Result<?> submit(UserBasicInfoDTO basicInfo, String userId) {
        log.info("📝 用户 {} 提交贷款申请（使用基础信息）: 产品={}, 金额={}, 期限={}",
                userId, basicInfo.getProductId(), basicInfo.getApplyAmount(), basicInfo.getApplyTerm());

        // 基础准入校验（与 submitApply 共用同一实现，8 个 throw 点不重复维护）
        ApplicationEligibilityChecker.BasicEligibility base = eligibilityChecker.checkBasics(
                userId, basicInfo.getProductId(), basicInfo.getApplyAmount(), basicInfo.getApplyTerm(),
                basicInfo.getDisbursementCardId());
        BigDecimal applyAmount = base.applyAmount();
        Integer applyTerm = base.applyTerm();
        Long disbursementCardId = base.disbursementCardId();

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
        ScorecardRoutingService.ScoreOutcome scoreOutcome =
                scorecardRoutingService.score(userId, userProfile);
        Integer scorecardScore = scoreOutcome.score();
        log.info("✅ 评分完成，得分: {}，模型: {}，来源: {}，缺失特征: {}/{}",
                scorecardScore, scoreOutcome.modelKey(), scoreOutcome.source(),
                scoreOutcome.missingFeatures(), scoreOutcome.requiredFeatures());

        // 阶段 2：产品阈值解析（产品配置优先；未配置时回退全局默认阈值）
        ProductThresholdResolver.ResolvedThresholds thresholds =
                productThresholdResolver.resolve(base.product());

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
            contractDraftService.generateQuietly(application.getApplicationId());
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
}
