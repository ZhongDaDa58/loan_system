package com.loan.service.impl;

import com.loan.entity.*;
import com.loan.entity.enums.ApplicationStatusEnum;
import com.loan.entity.vo.LoanApplicationVO;
import com.loan.entity.vo.Result;
import com.loan.entity.vo.loanDetail.ApplicantIdentityVO;
import com.loan.entity.vo.loanDetail.LoanApplicationBasicVO;
import com.loan.entity.vo.loanDetail.ScorecardDetailVO;
import com.loan.exception.BusinessException;
import com.loan.mapper.*;
import com.loan.service.ContractService;
import com.loan.service.LoanProductService;
import com.loan.service.RiskDecisionLogService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 申请查询（阶段四 4.2 第 5 步）
 *
 * <p><b>从哪来</b>：{@code LoanApplicationServiceImpl} 的 4 个查询方法
 * （{@code queryApplyProgress} / {@code getApplicationBasic} /
 * {@code getApplicantIdentity} / {@code getScorecardDetail}）连同
 * {@code repairMissingContract} / {@code calculateMonthlyPayment}。
 * <b>纯搬移，逻辑零变更</b>。
 *
 * <p><b>为什么查询和提交要分开</b>：
 * <ul>
 *   <li><b>关注点不同</b>：查询是只读 + 展示组装；提交是写 + 事务 + 风控决策链</li>
 *   <li><b>依赖不同</b>：查询依赖 Mapper 和展示服务，提交依赖评分卡/规则/定价/合同</li>
 *   <li><b>风险不同</b>：查询改坏了只影响展示；提交改坏了影响资金</li>
 * </ul>
 *
 * <p><b>这些方法都在 {@code LoanApplicationService} 接口上</b>，
 * 所以 {@code LoanApplicationServiceImpl} 保留同名方法做委托，接口签名不变。
 */
@Service
public class LoanApplicationQueryService {

    private static final Logger log = LoggerFactory.getLogger(LoanApplicationQueryService.class);

    @Resource
    private LoanApplicationMapper loanApplicationMapper;

    @Resource
    private LoanProductService loanProductService;

    @Resource
    private RiskDecisionLogService riskDecisionLogService;

    @Resource
    private ContractService contractService;

    @Resource
    private UserBankCardMapper userBankCardMapper;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private UserIdentityMapper userIdentityMapper;

    /** 阶段四 4.5：MapStruct 生成的转换（申请实体 → 审批端基本信息 VO） */
    @Resource
    private com.loan.convert.LoanApplicationBasicMapper basicMapper;

    // ==================== 用户进度 ====================

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
     * <p>背景：App 进度页仅在 contractPath 非空且 contractStatus=1 时显示「签署合同」按钮，
     * 合同缺失会导致用户看不到按钮、无法签约，流程中断。
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

    // ==================== 审核端详情 ====================

    public Result<LoanApplicationBasicVO> getApplicationBasic(String applicationId) {
        log.info("🔍 查询申请基本信息: {}", applicationId);

        LoanApplication application = loanApplicationMapper.selectByApplicationId(applicationId);
        if (application == null) {
            throw new BusinessException(404, "申请不存在");
        }

        // 阶段四 4.5：13 个同名字段改用 MapStruct 生成（编译期校验，漏字段会编译报错）
        // ── 映射了：applicationId / userId / productId / applyAmount / approvedAmount /
        //            applyTerm / scorecardScore / scorecardDecision / applicationStatus /
        //            statusDesc / applyTime / updateTime / disbursementCardId
        // ⚠️ approvedAmount 语义（S4c）：规则降额后的最终放款额；未降额时为 null
        LoanApplicationBasicVO basicVO = basicMapper.toVo(application);

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
        identityVO.setUserName(user.getRealName());
        identityVO.setKycStatus(user.getKycStatus());
        identityVO.setKycStatusDesc(ScorecardDetailAssembler.getKycStatusDesc(user.getKycStatus()));
        identityVO.setRegisterTime(user.getCreateTime());
        identityVO.setLastLoginTime(user.getUpdateTime());

        if (userIdentity != null) {
            // ⚠️ 修复（阶段四收尾，单元测试发现）：原代码在 if 之前就调 userIdentity.getUserRealName()，
            //    申请人未完成实名（无 user_identity 记录）时必然 NPE → 审核端 500。
            //    这不是拆分引入的，是拆分前就有的缺陷，搬到本类时原样保留、现在补上判空。
            identityVO.setUserRealName(userIdentity.getUserRealName());
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
            Map<String, Object> featureDetails = mapper.readValue(
                    riskLog.getFeatureDetails(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}
            );

            detailVO.setBaseScore(ScorecardDetailAssembler.calculateBaseScore());

            // 把 JSON 反序列化出的 Object 统一转成目标数值类型，避免 ClassCastException
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

    // ==================== 计算 ====================

    /**
     * 等额本息月供。
     *
     * <p>公式：{@code P × [i(1+i)^n] / [(1+i)^n − 1]}，其中 i = 年利率/12，n = 期数。
     *
     * <p><b>零利率单独处理</b>：{@code i=0} 时上述公式的分母为 0。本项目利率不会是 0，
     * 但产品配置允许填 0（用于无息活动），所以保留这个分支。
     */
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
}
