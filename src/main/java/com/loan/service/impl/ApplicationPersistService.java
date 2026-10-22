package com.loan.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loan.entity.LoanApplication;
import com.loan.entity.RiskDecisionLog;
import com.loan.entity.UserCreditProfile;
import com.loan.entity.dto.FeatureTransformationResultDTO;
import com.loan.entity.dto.LoanApplyDTO;
import com.loan.entity.dto.UserProfileDTO;
import com.loan.entity.enums.ApplicationStatusEnum;
import com.loan.entity.enums.ScorecardResultEnum;
import com.loan.entity.vo.Result;
import com.loan.exception.BusinessException;
import com.loan.mapper.LoanApplicationMapper;
import com.loan.metrics.RiskMetrics;
import com.loan.rule.RiskRuleEngine;
import com.loan.service.CreditScoreSyncService;
import com.loan.service.FeatureTransformationService;
import com.loan.service.RiskDecisionLogService;
import com.loan.service.ScorecardRoutingService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 申请单落库与留痕（阶段四 4.2 最后一步）
 *
 * <p><b>从哪来</b>：{@code LoanApplicationServiceImpl#submitApply} 的建单+后处理段（115 行）
 * 与 {@code persistL1RejectedApplication}（29 行），<b>纯搬移，逻辑零变更</b>。
 *
 * <p><b>为什么这两块放一起</b>：它们回答同一个问题 ——
 * <b>「决策已经算出来了，怎么把它变成一条完整可追溯的申请记录」</b>：
 * <ul>
 *   <li>建单：字段组装 + 状态机 + insert</li>
 *   <li>埋点：决策分布 / 降额 / 定价档位 / 评分分布（<b>位置很关键，必须在决策最终确定之后</b>）</li>
 *   <li>留痕：{@code risk_decision_log}（含特征快照、阈值来源、模型版本）</li>
 *   <li>副作用：信用分同步、合同草稿预生成</li>
 *   <li>L1 短路出口：反欺诈拦截时的"留档但未评分"特殊落库</li>
 * </ul>
 *
 * <p><b>⚠️ 事务边界说明</b>：本类<b>不加 {@code @Transactional}</b> ——
 * 与拆分前完全一致。原 {@code submitApply} 也没有事务，逐条 insert 各自提交。
 * 这不是理想状态（部分失败会留下不完整记录），但<b>改变事务语义是行为变更</b>，
 * 不属于"拆分重构"的范围，故原样保留。如需引入事务，应作为独立改动并评估回滚影响。
 */
@Service
public class ApplicationPersistService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationPersistService.class);

    @Resource
    private LoanApplicationMapper loanApplicationMapper;

    @Resource
    private RiskMetrics riskMetrics;

    @Resource
    private RiskDecisionLogService riskDecisionLogService;

    @Resource
    private FeatureTransformationService featureTransformationService;

    @Resource
    private CreditScoreSyncService creditScoreSyncService;

    @Resource
    private ContractDraftService contractDraftService;

    /**
     * 落库所需的上下文。
     *
     * <p>参数多达 11 个，用 record 收拢比长参数列表可读 ——
     * 而且这些值在主流程里都是现成的，不需要二次查库。
     */
    public record PersistRequest(
            String applicationId,
            String userId,
            String productId,
            BigDecimal applyAmount,
            Integer applyTerm,
            Long disbursementCardId,
            String idempotencyKey,
            Integer scorecardScore,
            ScorecardRoutingService.ScoreOutcome scoreOutcome,
            RiskDecisionChainService.DecisionResult decision,
            UserProfileDTO userProfile) {
    }

    /**
     * 建单 → 埋点 → 留痕 → 副作用，返回给前端的提示消息。
     *
     * @throws BusinessException 500 —— insert 影响行数不是 1
     */
    public Result<?> persist(PersistRequest req) {
        RiskDecisionChainService.DecisionResult decision = req.decision();
        BigDecimal approvedAmount = decision.approvedAmount();
        Integer scorecardScore = req.scorecardScore();
        ScorecardRoutingService.ScoreOutcome scoreOutcome = req.scoreOutcome();
        ScorecardResultEnum scorecardResult = decision.decision();

        LoanApplication application = new LoanApplication();
        application.setApplicationId(req.applicationId());
        application.setUserId(req.userId());
        application.setProductId(req.productId());
        application.setApplyAmount(req.applyAmount());
        application.setApprovedAmount(approvedAmount);   // P-025：null = 未降额
        application.setAppliedRate(decision.pricing().annualRate()); // 精细化定价：null = 未定价
        application.setPricingTier(decision.pricing().tierLabel());
        // 阶段四 4.3 第 ③ 层：把幂等键落库，与 user_id 组成唯一索引 uk_user_idem 兜底
        // （Redis 不可用/被绕过时，这里仍能挡住同一用户的重复提交；null 不参与唯一约束）
        application.setIdempotencyKey(req.idempotencyKey());
        application.setApplyTerm(req.applyTerm());
        application.setCreditScore(scorecardScore);
        application.setScorecardScore(scorecardScore);
        application.setDisbursementCardId(req.disbursementCardId());

        String decisionCode = ScorecardDetailAssembler.mapScorecardResultToDecisionCode(scorecardResult);
        application.setScorecardDecision(decisionCode);

        // ===== 1.5 业务指标埋点 =====
        // 位置很关键：此处「规则归并 + 降额 + 定价 + 多源完备性」均已完成，
        // 是「决策最终确定」的那一刻。若前移到评分之后，统计会把 AUTO_PASS 多算、
        // REJECT 漏算（数字看起来正常，实际是错的）。
        riskMetrics.decision(decisionCode);
        if (approvedAmount != null && approvedAmount.compareTo(req.applyAmount()) < 0) {
            riskMetrics.downgrade();          // 规则降额实际生效
        }
        if (decision.pricing() != null && decision.pricing().matched()) {
            riskMetrics.pricingTier(decision.pricing().tierLabel());
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
                req.userProfile(),
                ScorecardDetailAssembler.buildTransformationDetails(req.userProfile())
        );

        RiskDecisionLog riskLog = riskDecisionLogService.buildLogFromTransformation(
                application.getApplicationId(),
                req.userId(),
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
                    featureTransformationService.buildMultiSourceFeatures(req.userId())));
        } catch (Exception e) {
            log.warn("⚠️ 特征快照落库失败（不影响主流程）: {}", e.getMessage());
        }
        // 阶段 2：记录本次生效的产品阈值与来源（阈值分化可追溯）
        riskLog.setProductId(application.getProductId());
        riskLog.setAppliedAutoPass(decision.thresholds().autoPassScore());
        riskLog.setAppliedManualReview(decision.thresholds().manualReviewScore());
        riskLog.setThresholdSource(decision.thresholds().source());
        // 记录实际使用的评分模型（v3 单模型 / v4 多源多模型），便于分数追溯
        riskLog.setModelVersion(scoreOutcome.modelKey());
        riskDecisionLogService.logDecision(riskLog);

        creditScoreSyncService.syncScorecardToCreditScore(req.userId(), scorecardScore, application.getApplicationId());

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

    /**
     * L1 反欺诈即时拦截落档
     *
     * <p>设计文档 §2：第一层校验不通过<b>直接拒绝，不进入后续加工与评分</b>。
     * 与全局决策入口语义对齐（{@code RiskDecisionServiceImpl} 同样在评分前拦截 L1）：
     * <ul>
     *   <li><b>不调用评分卡</b> → {@code scorecard_score} 留空，表示"本次未评分"</li>
     *   <li>{@code credit_score} 为 NOT NULL → 沿用信用档案里的分数</li>
     *   <li>不生成合同草稿、不同步信用分</li>
     *   <li>规则命中明细已写入 {@code risk_rule_hit_log}，足以追溯
     *       （不写 {@code risk_decision_log}：该表语义是"评分卡决策留痕"，此处无评分卡决策）</li>
     * </ul>
     */
    public Result<?> persistL1Rejected(String applicationId, String userId, LoanApplyDTO applyDTO,
                                       BigDecimal applyAmount, Integer applyTerm, Long disbursementCardId,
                                       UserCreditProfile creditProfile, RiskRuleEngine.RuleOutcome l1Outcome) {
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
                .map(RiskRuleEngine.RuleHit::name)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining("、"));
        return Result.error(403, "很抱歉，您的申请未通过反欺诈校验"
                + (ruleNames.isBlank() ? "" : "（" + ruleNames + "）") + "，已被即时拦截");
    }
}
