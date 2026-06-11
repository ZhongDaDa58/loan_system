package com.loan.service.impl;
import com.loan.entity.LoanApplication;
import com.loan.entity.LoanIssue;
import com.loan.entity.MonthlyRepayment;
import com.loan.entity.RepaymentPlan;
import com.loan.entity.UserBankCard;
import com.loan.entity.dto.LoanIssueDTO;
import com.loan.entity.enums.ApplicationStatusEnum;
import com.loan.entity.enums.IssueStatusEnum;
import com.loan.entity.enums.RepaymentTypeEnum;
import com.loan.entity.enums.RepaymentStatusEnum;
import com.loan.entity.vo.Result;
import com.loan.exception.BusinessException;
import com.loan.mapper.LoanApplicationMapper;
import com.loan.mapper.LoanIssueMapper;
import com.loan.mapper.LoanProductMapper;
import com.loan.mapper.MonthlyRepaymentMapper;
import com.loan.mapper.RepaymentPlanMapper;
import com.loan.mapper.SysUserMapper;
import com.loan.mapper.UserBankCardMapper;
import com.loan.service.ContractService;
import com.loan.service.LoanIssueService;
import com.loan.util.IdUtil;
import com.loan.util.PayIntegrationUtil;
import com.loan.util.RepaymentCalculationUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

@Service
public class LoanIssueServiceImpl implements LoanIssueService {

    private static final Logger log = LoggerFactory.getLogger(LoanIssueServiceImpl.class);

    @Resource
    private LoanIssueMapper loanIssueMapper;

    @Resource
    private LoanApplicationMapper loanApplicationMapper;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private LoanProductMapper loanProductMapper;

    @Resource
    private RepaymentPlanMapper repaymentPlanMapper;

    @Resource
    private MonthlyRepaymentMapper monthlyRepaymentMapper;

    @Resource
    private UserBankCardMapper userBankCardMapper;

    @Resource
    private ContractService contractService; // ⭐ 注入合同服务

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> executeIssue(LoanIssueDTO issueDTO) {
        String applicationId = issueDTO.getApplicationId();

        LoanApplication application = loanApplicationMapper.selectByApplicationId(applicationId);
        if (application == null) {
            throw new BusinessException(400, "贷款申请不存在");
        }
        // 仅在申请为审核通过且合同已被用户签署（contractStatus == 2）后可放款
        if (!ApplicationStatusEnum.APPROVED.getCode().equals(application.getApplicationStatus())) {
            throw new BusinessException(400, "仅审核通过的申请可放款");
        }

        if (application.getContractStatus() == null || application.getContractStatus() != 2) {
            throw new BusinessException(400, "用户尚未完成合同签署，无法放款");
        }

        LoanIssue existingIssue = loanIssueMapper.selectByApplicationId(applicationId);
        if (existingIssue != null) {
            throw new BusinessException(400, "该申请已放款，无需重复操作");
        }

        Long disbursementCardId = application.getDisbursementCardId();
        UserBankCard disbursementCard = null;

        if (disbursementCardId != null) {
            disbursementCard = userBankCardMapper.selectById(disbursementCardId);
            if (disbursementCard == null) {
                throw new BusinessException(400, "申请时指定的放款银行卡不存在");
            }
            if (!"ACTIVE".equals(disbursementCard.getBindStatus())) {
                throw new BusinessException(400, "申请时指定的放款银行卡已解绑，请联系客服处理");
            }
            log.info("💳 使用申请时指定的银行卡放款: {}", disbursementCard.getAliasName());
        } else {
            log.info("ℹ️ 申请时未指定银行卡，将使用默认逻辑处理");
        }

        BigDecimal issueAmount = loanApplicationMapper.selectApplyAmountByApplicationId(applicationId);
        if (issueAmount == null || issueAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "放款金额异常");
        }

        boolean issueSuccess = PayIntegrationUtil.simulateIssue(application.getUserId(), issueAmount);
        if (!issueSuccess) {
            throw new BusinessException(500, "放款失败，请联系客服");
        }

        int updateBalanceRows = sysUserMapper.updateAccountBalance(application.getUserId(), issueAmount);
        if (updateBalanceRows != 1) {
            throw new BusinessException(500, "账户余额更新失败");
        }

        int updateAppStatusRows = loanApplicationMapper.updateStatus(applicationId, ApplicationStatusEnum.ISSUED.getCode());
        if (updateAppStatusRows != 1) {
            throw new BusinessException(500, "申请状态更新失败");
        }

        LoanIssue loanIssue = new LoanIssue();
        loanIssue.setIssueId(IdUtil.generateId());
        loanIssue.setApplicationId(applicationId);
        loanIssue.setUserId(application.getUserId());
        loanIssue.setIssueAmount(issueAmount);
        loanIssue.setIssueStatus(IssueStatusEnum.SUCCESS.getCode());
        loanIssue.setIssueTime(new Date());
        loanIssue.setDisbursementCardId(disbursementCardId);
        loanIssueMapper.insert(loanIssue);

        generateRepaymentPlan(application, issueAmount);

        BigDecimal currentBalance = sysUserMapper.selectAccountBalance(application.getUserId());
        String cardInfo = disbursementCard != null ? "，放款至银行卡：" + disbursementCard.getAliasName() : "";
        try {
            contractService.stampAndActivate(applicationId);
            System.out.println("✅ 放款成功，平台公章已加盖，合同正式生效");
        } catch (Exception e) {
            System.err.println("⚠️ 放款成功但合同盖章失败: " + e.getMessage()+"请联系客服处理");
            // 注意：这里通常不回滚放款，因为钱已经出去了，但需要人工介入处理合同问题
        }

        // 5. 更新申请状态为“已放款”
        loanApplicationMapper.updateStatus(applicationId, "issued");

        return Result.success("放款成功，已自动生成还款计划，当前账户余额：" + currentBalance + "元" + cardInfo);
    }

    /**
     * 生成还款计划（放款成功后自动调用）
     * @param application 贷款申请信息
     * @param issueAmount 放款金额（即贷款本金）
     */
    private void generateRepaymentPlan(LoanApplication application, BigDecimal issueAmount) {
        String applicationId = application.getApplicationId();
        String userId = application.getUserId();
        int term = application.getApplyTerm(); // 还款期数（与申请期限一致，单位：月）

        // 1. 获取产品年利率（用于计算利息）
        BigDecimal annualRate = loanProductMapper.selectInterestRateByProductId(application.getProductId());
        if (annualRate == null) {
            throw new BusinessException(500, "产品利率获取失败，无法生成还款计划");
        }

        // 2. 计算还款明细（默认使用等额本息，可扩展为前端选择还款方式）
        List<RepaymentCalculationUtil.MonthlyRepaymentDetail> calculationDetails =
                RepaymentCalculationUtil.calculateEqualPrincipalInterest(issueAmount, annualRate, term);

        // 3. 计算总还款金额、总利息（校验计算准确性）
        BigDecimal totalAmount = calculationDetails.stream()
                .map(RepaymentCalculationUtil.MonthlyRepaymentDetail::getRepaymentAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, BigDecimal.ROUND_HALF_UP);
        BigDecimal totalPrincipal = issueAmount; // 总本金=放款金额
        BigDecimal totalInterest = totalAmount.subtract(totalPrincipal).setScale(2, BigDecimal.ROUND_HALF_UP);

        // 4. 保存还款计划主表
        String planId = IdUtil.generateId();
        RepaymentPlan repaymentPlan = new RepaymentPlan();
        repaymentPlan.setPlanId(planId);
        repaymentPlan.setApplicationId(applicationId);
        repaymentPlan.setUserId(userId);
        repaymentPlan.setTotalAmount(totalAmount);
        repaymentPlan.setTotalPrincipal(totalPrincipal);
        repaymentPlan.setTotalInterest(totalInterest);
        repaymentPlan.setRepaymentType(RepaymentTypeEnum.EQUAL_PRINCIPAL_INTEREST.getCode()); // 等额本息
        repaymentPlan.setTermCount(term);
        repaymentPlan.setCreateTime(new Date());
        repaymentPlanMapper.insert(repaymentPlan);

        // 5. 批量生成每月还款明细并保存
        List<MonthlyRepayment> monthlyRepaymentList = new ArrayList<>();
        Date now = new Date(); // 放款时间作为计算还款日的基准
        for (RepaymentCalculationUtil.MonthlyRepaymentDetail calcDetail : calculationDetails) {
            // 计算到期还款日（放款日+N个月，如放款日为2024-01-15，第1期为2024-02-15）
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(now);
            calendar.add(Calendar.MONTH, calcDetail.getTerm()); // 第N期=放款后N个月
            Date dueDate = calendar.getTime();

            // 构建每月还款明细
            MonthlyRepayment monthlyRepayment = new MonthlyRepayment();
            monthlyRepayment.setRepaymentId(IdUtil.generateId());
            monthlyRepayment.setPlanId(planId);
            monthlyRepayment.setTerm(calcDetail.getTerm()); // 第1期、第2期...
            monthlyRepayment.setPrincipal(calcDetail.getPrincipal()); // 当期本金
            monthlyRepayment.setInterest(calcDetail.getInterest()); // 当期利息
            monthlyRepayment.setRepaymentAmount(calcDetail.getRepaymentAmount() );// 当期总还款额
            monthlyRepayment.setDueDate(dueDate); // 到期日
            monthlyRepayment.setRepaymentStatus(RepaymentStatusEnum.UNPAID.getCode()); // 初始状态：未还款

            monthlyRepaymentList.add(monthlyRepayment);
        }

        // 批量保存明细（高效插入）
        monthlyRepaymentMapper.batchInsert(monthlyRepaymentList);
    }
}
