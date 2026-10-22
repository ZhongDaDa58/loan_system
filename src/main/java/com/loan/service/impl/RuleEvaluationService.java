package com.loan.service.impl;

import com.loan.entity.RiskRuleHitLog;
import com.loan.entity.dto.UserProfileDTO;
import com.loan.metrics.RiskMetrics;
import com.loan.mapper.UserFaceLogMapper;
import com.loan.mapper.UserCreditProfileMapper;
import com.loan.mapper.UserMultiSourceDataMapper;
import com.loan.mapper.RiskRuleHitLogMapper;
import com.loan.rule.L1SignalAssembler;
import com.loan.rule.RiskRuleEngine;
import com.loan.scorecard.ModelRegistry;
import com.loan.service.MultiSourceService;
import com.loan.service.RiskRuleService;
import com.loan.util.RequestCache;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 分层规则评估（阶段四 4.2 第 4 步）
 *
 * <p><b>从哪来</b>：{@code LoanApplicationServiceImpl#evaluateRiskRulesByLayer}（130 行）
 * 连同 {@code recordUnavailableSignals} / {@code recordIfUnavailable} /
 * {@code missingRequiredMultiSources} 一起搬过来。**纯搬移，逻辑零变更**。
 *
 * <p><b>为什么值得单独成类</b>：
 * <ul>
 *   <li>它<b>不参与申请的事务</b> —— 规则评估异常绝不影响主流程（本类内部 catch 兜底），
 *       放在主类里会让人误以为它和建单在同一个事务边界内</li>
 *   <li>它有 <b>9 个依赖</b>，是主类依赖最密集的一块；独立后主类可以少注入 6 个 Mapper/Service</li>
 *   <li>L1（前置拦截）和 L3（后置修正）各调一次，是独立的一层职责</li>
 * </ul>
 *
 * <p><b>分层语义</b>（设计文档 §2 / §6）：
 * <ul>
 *   <li><b>L1 拦截层</b>：先验真、后算分。传 {@code score=null}，命中 REJECT 直接短路</li>
 *   <li><b>L3 修正层</b>：评分后再跑，可以降额/降级，但不改变"是否放款"这个前置判断</li>
 * </ul>
 */
@Service
public class RuleEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(RuleEvaluationService.class);

    /** 核心多源数据源（强制完备性检查）—— 如需放宽/收紧，只改这一行 */
    private static final List<String> REQUIRED_MULTI_SOURCES = List.of("credit", "device");

    @Resource
    private MultiSourceService multiSourceService;

    @Resource
    private UserFaceLogMapper userFaceLogMapper;

    @Resource
    private UserCreditProfileMapper userCreditProfileMapper;

    @Resource
    private UserMultiSourceDataMapper userMultiSourceDataMapper;

    @Resource
    private RiskRuleService riskRuleService;

    @Resource
    private RiskRuleEngine riskRuleEngine;

    @Resource
    private RiskRuleHitLogMapper riskRuleHitLogMapper;

    @Resource
    private ModelRegistry modelRegistry;

    @Resource
    private RiskMetrics riskMetrics;

    /**
     * 申请链路的分层规则评估（L1 反欺诈即时拦截 / L3 策略后置修正）
     *
     * <p>上下文 = 评分卡分数 + 申请金额 + 6 个基础特征 + <b>多源档案（非传统数据）</b>；
     * 命中结果写入 {@code risk_rule_hit_log}（影子规则也记录，shadow=1）。
     * <b>规则默认 enabled=0</b> → 未启用任何规则时不命中、不改变决策，审批行为与接入前一致。
     *
     * @param layer 只评估该层的规则（"L1" 拦截层 / "L3" 修正层）；传 null 表示不限制层
     * @param score L1 阶段传 null（设计 §2：先验真后算分）；规则条件里缺失字段按"不命中"处理
     */
    public RiskRuleEngine.RuleOutcome evaluate(String applicationId, String userId, String layer,
                                               Integer score, BigDecimal applyAmount,
                                               UserProfileDTO userProfile) {
        try {
            Map<String, Object> context = new LinkedHashMap<>();
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
            Map<String, Object> multiBlocks = null;
            try {
                // 2.2a 请求级缓存：本方法被 L1/L3 各调一次，不加缓存则多源档案要查两遍
                // （实测一次申请共 5 次 selectByUserId）
                com.loan.entity.vo.MultiSourceProfileVO profile = RequestCache.get(
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
                Object fp = deviceBlock instanceof Map<?, ?> m
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
            Map<String, Object> l1Signals = L1SignalAssembler.assemble(
                    multiBlocks,
                    RequestCache.get("faceLog:" + userId,
                            () -> userFaceLogMapper.selectLatestByUserId(userId)),
                    RequestCache.get("creditProfile:" + userId,
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
                Set<String> inModel = new HashSet<>(
                        defaultModel == null ? List.of() : defaultModel.featureNames());
                List<String> skipped = new ArrayList<>();
                if (multiBlocks != null) {
                    multiBlocks.forEach((src, block) -> {
                        if (block instanceof Map<?, ?> m) {
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
            List<com.loan.entity.RiskRule> runnable = RequestCache.get("riskRules:all",
                    () -> riskRuleService.list(null)).stream()
                    .filter(r -> Boolean.TRUE.equals(r.getEnabled()))
                    // 分层：L1 只跑拦截层、L3 只跑修正层（落实设计 §2 前置拦截 / §6 后置修正的层次语义）
                    .filter(r -> layer == null || layer.equalsIgnoreCase(r.getLayer()))
                    .sorted(Comparator.comparingInt(r -> r.getPriority() == null ? 999 : r.getPriority()))
                    .toList();

            RiskRuleEngine.RuleOutcome outcome = riskRuleEngine.evaluate(runnable, context);

            for (RiskRuleEngine.RuleHit hit : outcome.hits()) {
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
            return new RiskRuleEngine.RuleOutcome(
                    java.util.Collections.emptyList(), null, null, "规则评估异常");
        }
    }

    /**
     * 检查核心多源数据是否缺失（device + credit）
     *
     * <p>与「字段缺失 = 不命中 = 静默放行」的做法相反：这里把"缺数据"显式暴露出来，
     * 由调用方降级为人工审核，避免无数据用户被机器静默放过。
     *
     * @return 缺失的源名列表；空列表表示核心多源完备
     */
    public List<String> missingRequiredMultiSources(String userId) {
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

    /** 1.5：L1 层信号缺失埋点（仅 L1 统计，L3 重复调一次没有意义） */
    private void recordUnavailableSignals(Map<String, Object> signals, String layer) {
        if (!"L1".equalsIgnoreCase(layer)) {
            return;
        }
        recordIfUnavailable(signals, "device_available", "device");
        recordIfUnavailable(signals, "liveness_available", "liveness");
        recordIfUnavailable(signals, "geo_available", "geo");
        recordIfUnavailable(signals, "behavior_available", "behavior");
    }

    private void recordIfUnavailable(Map<String, Object> signals,
                                     String flagKey, String signalName) {
        Object v = signals == null ? null : signals.get(flagKey);
        if (v instanceof Number n && n.intValue() == 0) {
            riskMetrics.signalUnavailable(signalName);
        }
    }
}
