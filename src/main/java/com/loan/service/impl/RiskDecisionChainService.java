package com.loan.service.impl;

import com.loan.entity.LoanProduct;
import com.loan.entity.dto.UserProfileDTO;
import com.loan.entity.enums.ScorecardResultEnum;
import com.loan.rule.RiskRuleEngine;
import com.loan.scorecard.ProductThresholdResolver;
import com.loan.service.PricingService;
import com.loan.util.RiskControlUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 申请决策链（阶段四 4.2 第 6 步 · 第二段）
 *
 * <p><b>从哪来</b>：{@code LoanApplicationServiceImpl#submitApply} 的决策段（73 行），
 * <b>纯搬移，逻辑与日志逐行保留</b>。
 *
 * <p><b>这一步在做什么</b>（顺序不可打乱）：
 * <ol>
 *   <li><b>阈值解析</b>：产品配置优先，未配置回退全局默认</li>
 *   <li><b>评分卡决策</b>：分数 + 双阈值 → AUTO_PASS / MANUAL_REVIEW / AUTO_REJECT</li>
 *   <li><b>L3 后置修正</b>：评分后再跑策略规则，可降级</li>
 *   <li><b>L1 非 REJECT 动作归并</b>：L1 的 MANUAL/DOWNGRADE/FLAG 不能被丢弃
 *       （否则设计 §8 要求的"命中存疑标签 → 转人工"通路会断）</li>
 *   <li><b>规则降额</b>：{@code action_param} 语义是<b>保留比例</b>（"0.6" 只放 60%）</li>
 *   <li><b>精细化定价</b>：按分数匹配利率档位</li>
 *   <li><b>多源完备性降级</b>：核心多源缺失时把 AUTO_PASS 降为人工，而不是静默放行</li>
 * </ol>
 *
 * <p><b>为什么这段能干净地抽出来</b>：它<b>没有提前 return、不抛业务异常、不写库</b>
 * —— 纯"拿输入算决策"。L1 的短路拒绝在上游处理（那时还没进入本类）。
 *
 * <p><b>⚠️ 输出必须显式返回</b>：{@code thresholds}（决策留痕要用）、
 * {@code decision}（建单/状态机要用）、{@code approvedAmount}（放款额）、
 * {@code pricing}（利率）。不能靠调用方再算一遍 —— 再算一次就可能与本次决策不一致。
 */
@Service
public class RiskDecisionChainService {

    private static final Logger log = LoggerFactory.getLogger(RiskDecisionChainService.class);

    @Resource
    private ProductThresholdResolver productThresholdResolver;

    @Resource
    private RuleEvaluationService ruleEvaluationService;

    @Resource
    private PricingService pricingService;

    /**
     * 决策链的产出。
     *
     * @param thresholds     本次生效的阈值（含来源，用于 risk_decision_log 留痕）
     * @param decision       最终决策（已合并 L3 + L1 非 REJECT 动作 + 多源降级）
     * @param approvedAmount 规则降额后的批准金额；{@code null} = 未降额（放款回退 apply_amount）
     * @param pricing        定价结果；{@code null} 或未命中表示回退产品自带利率
     */
    public record DecisionResult(
            ProductThresholdResolver.ResolvedThresholds thresholds,
            ScorecardResultEnum decision,
            BigDecimal approvedAmount,
            PricingService.PricingOutcome pricing) {
    }

    /**
     * 执行决策链。
     *
     * @param applicationId 申请 ID（规则命中流水要按它落库）
     * @param userId        用户 ID
     * @param product       产品对象（阈值解析与定价档位都要用；**不传 productId 是为了
     *                      避免调用方把刚查到的 product 又丢掉再查一次**）
     * @param scorecardScore 评分卡分数
     * @param applyAmount   申请金额（降额基数）
     * @param userProfile   评分入参（规则上下文要用）
     * @param l1Outcome     L1 层结果（非 REJECT 动作要参与归并）
     */
    public DecisionResult decide(String applicationId, String userId, LoanProduct product,
                                 Integer scorecardScore, BigDecimal applyAmount,
                                 UserProfileDTO userProfile, RiskRuleEngine.RuleOutcome l1Outcome) {
        // 阶段 2：产品阈值解析（产品配置优先；未配置时回退全局默认阈值）
        ProductThresholdResolver.ResolvedThresholds thresholds = productThresholdResolver.resolve(product);

        ScorecardResultEnum scorecardResult = RiskControlUtil.evaluateScorecard(
                scorecardScore,
                thresholds.autoPassScore(),
                thresholds.manualReviewScore()
        );

        log.info("🎯 评分卡决策: {}, 分数: {}", scorecardResult.getDesc(), scorecardScore);

        // ===== 阶段 3-b：L3 策略决策层「后置修正」（输入 = 分 + 标签，不重算信用分）=====
        // 优先级 REJECT > MANUAL > DOWNGRADE > FLAG；规则默认 enabled=0 → 无命中
        RiskRuleEngine.RuleOutcome ruleOutcome =
                ruleEvaluationService.evaluate(applicationId, userId, "L3", scorecardScore, applyAmount, userProfile);
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
        PricingService.PricingOutcome pricing = pricingService.resolveRate(product.getProductId(), scorecardScore);

        // ===== 阶段 3-c：多源数据完备性强制（device + credit）=====
        // 不再"字段缺失即静默放行"：核心多源缺失时，把「自动通过」降为「人工审核」，
        // 由审核员判断属于"用户未授权/未同步多源"还是"真实的低风险客户"。
        // 只降级 AUTO_PASS；AUTO_REJECT 与 MANUAL_REVIEW 不受影响。
        List<String> missingMultiSources = ruleEvaluationService.missingRequiredMultiSources(userId);
        if (scorecardResult == ScorecardResultEnum.AUTO_PASS && !missingMultiSources.isEmpty()) {
            log.warn("⚠️ 多源数据不足（缺少 {}），自动通过降为转人工：申请={}",
                    missingMultiSources, applicationId);
            scorecardResult = ScorecardResultEnum.MANUAL_REVIEW;
        }

        return new DecisionResult(thresholds, scorecardResult, approvedAmount, pricing);
    }
}
