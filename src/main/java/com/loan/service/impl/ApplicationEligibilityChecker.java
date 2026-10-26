package com.loan.service.impl;

import com.loan.entity.LoanProduct;
import com.loan.entity.OverdueBlacklist;
import com.loan.entity.MonthlyRepayment;
import com.loan.entity.SysUser;
import com.loan.entity.UserBankCard;
import com.loan.entity.UserCreditProfile;
import com.loan.entity.dto.LoanApplyDTO;
import com.loan.entity.dto.UserProfileDTO;
import com.loan.exception.BusinessException;
import com.loan.mapper.MonthlyRepaymentMapper;
import com.loan.mapper.SysUserMapper;
import com.loan.mapper.UserBankCardMapper;
import com.loan.mapper.UserCreditProfileMapper;
import com.loan.service.LoanProductService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 申请准入校验（阶段四 4.2 第 6 步 · 第一段）
 *
 * <p><b>从哪来</b>：{@code LoanApplicationServiceImpl#submitApply} 开头的校验段（45 行），
 * <b>纯搬移，8 个 throw 点的类型与文案逐字保留</b>。
 *
 * <p><b>为什么单独成类</b>：
 * <ul>
 *   <li><b>它是"申请能不能提交"的独立判断</b>，与"决策链"（L1→评分→L3→定价）无关</li>
 *   <li><b>8 个 throw 集中在一处</b>，将来要加校验（如反欺诈名单、地域限制）只需改这里</li>
 *   <li>它的输出是一组"校验后的上下文"，用 record 显式表达，
 *       避免主流程靠一堆局部变量隐式传递</li>
 * </ul>
 *
 * <p><b>⚠️ 为什么返回 record 而不是"校验完再查一遍"</b>：
 * 这些数据在校验时已经查过库（用户、产品、银行卡、信用档案），
 * 靠调用方二次查询既多一次 DB 往返，也可能读到不一致的数据（校验时合法、使用时已变）。
 *
 * <p><b>校验顺序是刻意的</b>：先身份（403，用户无资格）→ 再逾期（400，业务限制）→
 * 再产品/金额/期限（400，参数问题）→ 再银行卡 → 最后信用档案。
 * 顺序影响用户看到的错误提示，改动前要确认产品是否认可新的提示优先级。
 */
@Service
public class ApplicationEligibilityChecker {

    private static final Logger log = LoggerFactory.getLogger(ApplicationEligibilityChecker.class);

    @Resource
    private SysUserMapper sysUserMapper;

    /** 逾期黑名单（第 6 步）：严重逾期结清后仍有观察期 */
    @Resource
    private com.loan.service.OverdueBlacklistService overdueBlacklistService;

    @Resource
    private MonthlyRepaymentMapper monthlyRepaymentMapper;

    @Resource
    private LoanProductService loanProductService;

    @Resource
    private UserBankCardMapper userBankCardMapper;

    @Resource
    private UserCreditProfileMapper userCreditProfileMapper;

    /** 阶段四 4.5：MapStruct 生成的转换（编译期生成实现类） */
    @Resource
    private com.loan.convert.UserProfileMapper userProfileMapper;

    /**
     * 校验通过后的上下文。
     *
     * <p>字段都是"后续决策链要用的东西"，校验过程中顺带查好了，不重复查库。
     *
     * @param product            通过校验的产品（后续用于阈值解析、定价、决策留痕）
     * @param applyAmount        申请金额（已确认在 [min, max] 内）
     * @param applyTerm          申请期限（已确认在 [minTerm, maxTerm] 内）
     * @param disbursementCardId 放款卡 ID，可为 {@code null}（使用默认卡）
     * @param creditProfile      信用档案（含 credit_score，非空）
     * @param userProfile        由信用档案派生的评分入参（避免调用方重复构造）
     */
    public record Eligibility(
            LoanProduct product,
            BigDecimal applyAmount,
            Integer applyTerm,
            Long disbursementCardId,
            UserCreditProfile creditProfile,
            UserProfileDTO userProfile) {
    }

    /**
     * 基础准入校验的结果（不含信用档案）。
     *
     * <p>"提交完整信息"链路（{@code submitApplyWithBasicInfo}）用这个 ——
     * 它的特征来自用户填写的 {@code UserBasicInfoDTO}，不需要读信用档案。
     */
    public record BasicEligibility(
            LoanProduct product,
            BigDecimal applyAmount,
            Integer applyTerm,
            Long disbursementCardId) {
    }

    /**
     * 基础准入校验：身份 → 逾期 → 产品/金额/期限 → 银行卡。
     *
     * <p><b>两条申请链路共用这一段</b>（{@code submitApply} 与
     * {@code submitApplyWithBasicInfo}）—— 此前是两份逐字相同的代码，
     * 8 个 throw 点、8 段提示文案都得两处同改，很容易改漏一处导致行为不一致。
     *
     * @throws BusinessException 403 —— 未实名认证
     * @throws BusinessException 400 —— 逾期未结清 / 金额期限越界 / 银行卡无效
     */
    public BasicEligibility checkBasics(String userId, String productId,
                                        BigDecimal applyAmount, Integer applyTerm,
                                        Long disbursementCardId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || user.getKycStatus() == null || user.getKycStatus() != 1) {
            throw new BusinessException(403, "您尚未完成实名认证，请先进行身份认证后再申请贷款");
        }

        // ===== 逾期黑名单（第 6 步）=====
        // 放在逾期检查之前：黑名单是人工/系统做出的"更强结论"（严重逾期结清后仍需观察），
        // 提示语也更准确 —— 说"您已被列入黑名单"，而不是让客户以为自己只是有点逾期。
        OverdueBlacklist blacklist = overdueBlacklistService.getActive(userId);
        if (blacklist != null) {
            String until = blacklist.getExpireAt() == null
                    ? "（长期有效）"
                    : "（有效期至 " + new java.text.SimpleDateFormat("yyyy-MM-dd")
                            .format(blacklist.getExpireAt()) + "）";
            throw new BusinessException(400,
                    "您因逾期已被列入黑名单" + until + "，当前无法申请贷款，请联系客服处理");
        }

        // ===== 逾期校验（逾期处理功能升级 第 4 步：从"一刀切"改成分级）=====
        // 之前：只要有任何逾期就拒绝，逾期 1 天和 90 天待遇完全一样。
        // 现在：按最高等级差异化 —— M2/M3 拒绝（提示语不同），M1 放行但留痕。
        //
        // ⚠️ M1 的"额度打折"本次未做：它要改 approvedAmount 整条链路（决策链 → 落库），
        //    涉及真实金额，风险比拦截高，留到单独一步评估（见开发文档 §3.4）。
        List<MonthlyRepayment> overdueRecords = monthlyRepaymentMapper.selectOverdueByUserId(userId);
        if (!overdueRecords.isEmpty()) {
            String worst = worstOverdueLevel(overdueRecords);
            int count = overdueRecords.size();
            switch (worst) {
                case "M3" -> throw new BusinessException(400,
                        "您有 " + count + " 笔逾期且已超过 60 天（严重逾期），当前无法申请贷款，请联系客服处理");
                case "M2" -> throw new BusinessException(400,
                        "您有 " + count + " 笔逾期已超过 30 天，请先结清欠款后再申请");
                case "M1" -> {
                    // 早期逾期（1-30 天）：放行，但记录一下——便于后续统计"带逾期申请"的比例
                    log.warn("⚠️ 用户 {} 存在 M1 早期逾期（{} 笔），本次放行", userId, count);
                }
                default -> throw new BusinessException(400,
                        "您有 " + count + " 笔逾期未还款记录，请先结清欠款后再申请");
            }
        }

        LoanProduct product = loanProductService.validateProduct(productId);

        if (applyAmount.compareTo(product.getMinAmount()) < 0 || applyAmount.compareTo(product.getMaxAmount()) > 0) {
            throw new BusinessException(400, "申请金额需在" + product.getMinAmount() + "-" + product.getMaxAmount() + "元之间");
        }
        if (applyTerm < product.getMinTerm() || applyTerm > product.getMaxTerm()) {
            throw new BusinessException(400, "申请期限需在" + product.getMinTerm() + "-" + product.getMaxTerm() + "月之间");
        }

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

        return new BasicEligibility(product, applyAmount, applyTerm, disbursementCardId);
    }

    /**
     * 完整准入校验 = 基础校验 + 信用档案存在性。
     *
     * <p>{@code submitApply} 用这个：它不接收用户填写的特征，而是从信用档案读取
     * （"一次填写、后续复用"）。所以必须先确认档案存在且有评分。
     *
     * @throws BusinessException 403 —— 未实名认证
     * @throws BusinessException 400 —— 逾期未结清 / 金额期限越界 / 银行卡无效 / 无信用档案
     */
    public Eligibility check(String userId, LoanApplyDTO applyDTO) {
        BasicEligibility basics = checkBasics(
                userId,
                applyDTO.getProductId(),
                applyDTO.getApplyAmount(),
                applyDTO.getApplyTerm(),
                applyDTO.getDisbursementCardId());

        // 从信用档案读取个人特征数据（无需重复填写）
        UserCreditProfile creditProfile = userCreditProfileMapper.selectByUserId(userId);
        if (creditProfile == null || creditProfile.getCreditScore() == null) {
            throw new BusinessException(400, "请先创建信用档案，完善个人信息后再申请贷款");
        }

        // 阶段四 4.5：改用 MapStruct 生成的转换（编译期生成，字段名不一致会编译报错）
        UserProfileDTO userProfile = userProfileMapper.toDto(creditProfile);

        return new Eligibility(basics.product(), basics.applyAmount(), basics.applyTerm(),
                basics.disbursementCardId(), creditProfile, userProfile);
    }

    // ==================== 逾期分级（第 4 步）====================

    /**
     * 取一组逾期记录里的最高等级（M3 &gt; M2 &gt; M1）。
     *
     * <p><b>为什么按"最高"而不是"数量"</b>：一笔 M3 比十笔 M1 更严重。
     *
     * <p>等级为 {@code null} 的（V4 之前的脏数据没回填到的）返回 {@code UNKNOWN}，
     * 让调用方的 switch 落到 default 分支（拒绝）—— <b>不能按 M1 兜底</b>，
     * 因为 M1 是放行的，那样会静默放掉真正的严重逾期。
     */
    private static String worstOverdueLevel(List<MonthlyRepayment> records) {
        String worst = null;
        for (MonthlyRepayment r : records) {
            // ⚠️ 等级为 null（V4 前的脏数据未回填）不能按 M1 兜底 —— M1 分支是放行的，
            //    那样会静默放掉真正的严重逾期。用 UNKNOWN 走 switch 的 default 分支（拒绝）。
            String lv = r.getOverdueLevel() == null ? "UNKNOWN" : r.getOverdueLevel();
            if (worst == null || rank(lv) > rank(worst)) {
                worst = lv;
            }
        }
        return worst == null ? "M1" : worst;
    }

    private static int rank(String level) {
        return switch (level) {
            case "M3" -> 3;
            case "M2" -> 2;
            default -> 1;
        };
    }
}
