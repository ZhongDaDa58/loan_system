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
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
public class LoanApplicationServiceImpl implements LoanApplicationService {

    private static final Logger log = LoggerFactory.getLogger(LoanApplicationServiceImpl.class);

    @Resource
    private LoanApplicationMapper loanApplicationMapper;

    /** 阶段 3：规则引擎接入申请链路（规则默认停用 → 无命中 → 审批行为与接入前一致） */


    /** 阶段 C1：活体结果读取（第一层「信息校验先行」信号装配用） */

    /** 阶段 C5：设备指纹跨账号检索（第一层信号装配用） */

    /** 评分路由服务（第二层口径统一：建档 / 申请 / 重算三处共用同一实现） */
    @Resource
    private com.loan.service.ScorecardRoutingService scorecardRoutingService;

    /** 模型注册表（T3：用于算出"落选特征"—— 在多源宽表里但未进入当前默认模型的字段） */

    /** 多源档案（供规则上下文使用非传统数据） */

    /** 阶段四 4.3：幂等控制（防连点重复提交） */
    @Resource
    private com.loan.service.IdempotencyService idempotencyService;

    /** 阶段四 4.2：分层规则评估（L1 拦截 / L3 修正）—— 依赖密集（9 个），已独立成类 */
    @Resource
    private RuleEvaluationService ruleEvaluationService;

    /** 阶段四 4.2：申请准入校验（KYC / 逾期 / 金额期限 / 银行卡 / 信用档案） */
    @Resource
    private ApplicationEligibilityChecker eligibilityChecker;

    /** 阶段四 4.2：决策链（阈值 → 评分决策 → L3 修正 → L1 归并 → 降额 → 定价 → 多源降级） */
    @Resource
    private RiskDecisionChainService decisionChainService;

    /** 阶段四 4.2：申请查询（只读职责，与提交流程分离） */
    @Resource
    private LoanApplicationQueryService queryService;

    /** 阶段四 4.2：完整信息链路的申请提交 */
    @Resource
    private BasicInfoApplyService basicInfoApplyService;

    /** 阶段四 4.2：申请单落库与留痕（建单 / 埋点 / risk_decision_log / L1 短路落档） */
    @Resource
    private ApplicationPersistService persistService;


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

    /**
     * 提交贷款申请（信用档案链路）。
     *
     * <p><b>阶段四 P2：加事务之前的问题</b>
     * <p>这里原来<b>没有</b> {@code @Transactional} —— 逐条 insert 各自提交，于是会出现"半截"数据：
     * <ul>
     *   <li>申请单建好了，但 {@code risk_decision_log} 写失败 → <b>审批详情页拿不到评分依据</b></li>
     *   <li>{@code risk_rule_hit_log} 写了（L1/L3 各一次），但后面建单失败 → <b>孤儿命中流水</b></li>
     *   <li>信用分同步失败 → 申请单已存在但档案分没跟上</li>
     * </ul>
     *
     * <p><b>加事务后的边界（刻意保留不回滚的东西）</b>：
     * <table border="1">
     *   <tr><th>操作</th><th>是否回滚</th><th>原因</th></tr>
     *   <tr><td>建单 / 决策日志 / 规则命中 / 信用分同步</td><td><b>回滚</b></td>
     *       <td>它们是"这笔申请的一次完整决策"，缺一个就是脏数据</td></tr>
     *   <tr><td>合同草稿 PDF</td><td><b>不回滚</b></td>
     *       <td>文件系统不参与事务。但它内部 catch 了异常（尽力而为的预生成），
     *           且查询进度时会兜底补生成 —— 事务提交了文件在，回滚了文件成孤儿（可清理，不影响业务）</td></tr>
     *   <tr><td>Micrometer 指标</td><td><b>不回滚</b></td>
     *       <td>指标的语义是"决策发生过"，回滚后重试会再记一次，比"漏记"更符合监控直觉</td></tr>
     *   <tr><td>Redis 幂等键</td><td><b>回滚时释放</b></td>
     *       <td>{@code IdempotencyService} 里注册了 {@code TransactionSynchronization}：
     *           提交则保留键（真正防重复）、回滚则释放键（用户可立即重试）。
     *           <b>这段代码早就写好了，但因为本方法没有事务，一直没生效</b> ——
     *           之前"提交失败后要等 5 分钟才能重试"的遗留问题，由本次加事务一并解决。</td></tr>
     * </table>
     *
     * <p><b>为什么 {@code rollbackFor = Exception.class}</b>：Spring 默认只对 {@code RuntimeException}
     * 回滚，受检异常不触发。本项目业务异常都是 {@code RuntimeException}（{@code BusinessException}），
     * 但显式写上能防住将来引入受检异常时的静默漏回滚。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> submitApply(LoanApplyDTO applyDTO, String userId) {
        // 阶段四 4.3：幂等检查（第 ② 层：Redis SETNX）
        // 同一 Idempotency-Key 在 5 分钟内重复提交 → 409；未带该头的老客户端放行（返回 null）
        // ⚠️ 本方法有事务后，acquire 会注册事务同步：事务回滚时自动释放这个键
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
            return persistService.persistL1Rejected(applicationId, userId, applyDTO, applyAmount,
                    applyTerm, disbursementCardId, creditProfile, l1Outcome);
        }

        log.info("🔍 开始评分（优先多源多模型，多源不足时回落单模型）...");
        com.loan.service.ScorecardRoutingService.ScoreOutcome scoreOutcome =
                scorecardRoutingService.score(userId, userProfile);
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

        // 阶段四 4.2：建单 + 埋点 + 留痕 + 副作用已移到 ApplicationPersistService
        // （插单 / 决策分布埋点 / risk_decision_log 含特征快照 / 信用分同步 / 合同预生成）
        return persistService.persist(new ApplicationPersistService.PersistRequest(
                applicationId, userId, applyDTO.getProductId(), applyAmount, applyTerm,
                disbursementCardId, idemKey, scorecardScore, scoreOutcome, decision, userProfile));
    }

    @Override
    public Result<?> submitApplyWithBasicInfo(UserBasicInfoDTO basicInfo,  String userId) {
        // 阶段四 4.2 第 7 步：完整信息链路已移到 BasicInfoApplyService
        // （数据校验 → 特征转换 → 评分 → 决策 → 建单 → 埋点 → 档案回写 → 合同）
        return basicInfoApplyService.submit(basicInfo, userId);
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
}
