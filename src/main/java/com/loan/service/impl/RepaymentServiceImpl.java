package com.loan.service.impl;
import com.loan.entity.MonthlyRepayment;
import com.loan.entity.UserBankCard;
import com.loan.entity.vo.DueItemVO;
import com.loan.entity.vo.DueItemsVO;
import com.loan.entity.vo.RepaymentPlanListVO;
import com.loan.entity.vo.RepaymentPlanVO;
import com.loan.entity.dto.RepaymentSubmitDTO;
import com.loan.entity.enums.RepaymentStatusEnum;
import com.loan.entity.vo.MonthlyRepaymentVO;
import com.loan.entity.vo.Result;
import com.loan.exception.BusinessException;
import com.loan.mapper.MonthlyRepaymentMapper;
import com.loan.mapper.RepaymentPlanMapper;
import com.loan.mapper.SysUserMapper;
import com.loan.mapper.UserBankCardMapper;
import com.loan.service.MockBankCardService;
import com.loan.service.CollectionTaskService;
import com.loan.service.RepaymentService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

@Service
public class RepaymentServiceImpl implements RepaymentService {

    private static final Logger log = LoggerFactory.getLogger(RepaymentServiceImpl.class);

    @Resource
    private RepaymentPlanMapper repaymentPlanMapper;

    @Resource
    private MonthlyRepaymentMapper monthlyRepaymentMapper;

    /** 催收任务（第 6 步-c）：还款成功后自动关闭该期任务 */
    @Resource
    private CollectionTaskService collectionTaskService;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private UserBankCardMapper userBankCardMapper;

    @Resource
    private MockBankCardService mockBankCardService;

    @Override
    public Result<List<RepaymentPlanVO>> queryRepaymentPlan(String userId) {
        // 1. 查询还款计划
        List<RepaymentPlanVO> planVO = repaymentPlanMapper.selectByUserId(userId);
        if (planVO == null) {
            throw new BusinessException(400, "暂无还款计划");
        }

        // 2. 查询每月还款明细（简化：实际需关联plan_id查询，此处省略联表，直接查询用户下所有明细）
        // 注：实际开发需新增MonthlyRepaymentMapper.selectByPlanId方法，关联plan_id查询
        return Result.success(planVO);
    }

    @Override
    public Result<RepaymentPlanVO> queryRepaymentPlanDetail(String applicationId){
        RepaymentPlanVO planVO = repaymentPlanMapper.selectByApplicationId(applicationId);
        if (planVO == null) {
            throw new BusinessException(400, "暂无还款计划");
        }
        List<MonthlyRepaymentVO> monthlyRepaymentVOList = monthlyRepaymentMapper.selectByPlanId(planVO.getPlanId());
        planVO.setMonthlyRepaymentList(monthlyRepaymentVOList);
        return Result.success(planVO);

    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> submitRepayment(RepaymentSubmitDTO repaymentDTO, String userId) {
        String repaymentId = repaymentDTO.getRepaymentId();
        BigDecimal submitAmount = repaymentDTO.getRepaymentAmount().setScale(2, BigDecimal.ROUND_HALF_UP);

        MonthlyRepayment monthlyRepayment = monthlyRepaymentMapper.selectById(repaymentId);
        if (monthlyRepayment == null) {
            throw new BusinessException(400, "还款明细不存在");
        }

        if (!RepaymentStatusEnum.UNPAID.getCode().equals(monthlyRepayment.getRepaymentStatus()) && !
                RepaymentStatusEnum.OVERDUE.getCode().equals(monthlyRepayment.getRepaymentStatus())) {
            throw new BusinessException(400, "该期款项已还款或逾期，无需重复操作");
        }

        // 应还金额 = 当期应还 + 罚息（逾期处理升级第 3 步-b）
        //
        // 罚息由 OverdueCheckService 每日累加到 monthly_repayment.penalty_amount；
        // 未逾期/存量记录该字段为 0，因此对老数据完全等价于原来的"只校验 repaymentAmount"。
        //
        // ⚠️ 与展示层保持一致：queryDueItems 返回的 totalDue 也是 repaymentAmount + penalty。
        //    两处必须同步改 —— 否则用户看到的应还金额和提交时的校验值不一致，
        //    前端按 totalDue 提交会被判"还款金额不一致"。
        BigDecimal penalty = monthlyRepayment.getPenaltyAmount() != null
                ? monthlyRepayment.getPenaltyAmount()
                : BigDecimal.ZERO;
        BigDecimal expectedAmount = monthlyRepayment.getRepaymentAmount().add(penalty);

        if (!expectedAmount.equals(submitAmount)) {
            // 保留实际值便于排查
            log.warn("还款金额不一致 submitAmount={} expected={}（本金利息 {} + 罚息 {}）",
                    submitAmount, expectedAmount, monthlyRepayment.getRepaymentAmount(), penalty);
            throw new BusinessException(400, "还款金额不一致");
        }

        Long paymentCardId = repaymentDTO.getPaymentCardId();
        UserBankCard paymentCard = null;

        if (paymentCardId != null) {
            paymentCard = userBankCardMapper.selectById(paymentCardId);
            if (paymentCard == null) {
                throw new BusinessException(400, "指定的还款银行卡不存在");
            }
            if (!userId.equals(paymentCard.getUserId())) {
                throw new BusinessException(400, "无权使用该银行卡进行还款");
            }
            if (!"ACTIVE".equals(paymentCard.getBindStatus())) {
                throw new BusinessException(400, "该银行卡已解绑，请选择其他卡片");
            }

            mockBankCardService.checkBalance(paymentCard.getPoolCardId(), submitAmount);
            log.info("✅ 使用指定银行卡还款: {}, 余额充足", paymentCard.getAliasName());
        } else {
            List<UserBankCard> userCards = userBankCardMapper.selectByUserId(userId);
            if (userCards == null || userCards.isEmpty()) {
                throw new BusinessException(400, "您尚未绑定任何银行卡，请先绑卡后再还款");
            }

            boolean hasSufficientBalance = false;
            for (UserBankCard card : userCards) {
                try {
                    mockBankCardService.checkBalance(card.getPoolCardId(), submitAmount);
                    hasSufficientBalance = true;
                    paymentCard = card;
                    log.info("✅ 自动选择银行卡: {} (余额充足)", card.getAliasName());
                    break;
                } catch (BusinessException e) {
                    log.debug("银行卡 {} 余额不足，尝试下一张", card.getAliasName());
                }
            }

            if (!hasSufficientBalance) {
                throw new BusinessException(400, "所有绑定银行卡余额均不足，请充值后重试");
            }
        }

        BigDecimal accountBalance = sysUserMapper.selectAccountBalance(userId);
        if (accountBalance.compareTo(submitAmount) < 0) {
            throw new BusinessException(400, "账户余额不足，当前余额：" + accountBalance + "元");
        }

        int updateBalanceRows = sysUserMapper.updateAccountBalance(userId, submitAmount.negate());
        if (updateBalanceRows != 1) {
            throw new BusinessException(500, "余额扣减失败");
        }

        if (paymentCard != null) {
            mockBankCardService.updateBalance(paymentCard.getPoolCardId(), submitAmount, "OUT");
            log.info("💳 已从银行卡 {} 扣除还款金额: {}", paymentCard.getAliasName(), submitAmount);
        }

        int updateStatusRows = monthlyRepaymentMapper.updateRepaymentStatus(
                repaymentId, RepaymentStatusEnum.PAID.getCode(), new Date()
        );
        if (updateStatusRows != 1) {
            throw new BusinessException(500, "还款状态更新失败");
        }

        // 还款成功 -> 关掉这期的催收任务（第 6 步-c）
        //
        // ⚠️ 必须包 try-catch：关任务失败**绝不能影响还款本身**。
        //    还款才是钱的流向，催收任务是辅助运营；而且本方法在事务里，
        //    不 catch 的话关任务抛异常会把已扣款的还款一起回滚。
        try {
            int closed = collectionTaskService.closeByRepayment(
                    repaymentId, "客户已还款（自动关闭）");
            if (closed > 0) {
                log.info("期次 {} 还款成功，自动关闭 {} 个催收任务", repaymentId, closed);
            }
        } catch (Exception e) {
            log.error("期次 {} 还款成功但催收任务关闭失败（不影响还款）", repaymentId, e);
        }

        BigDecimal currentBalance = sysUserMapper.selectAccountBalance(userId);
        return Result.success("第" + monthlyRepayment.getTerm() + "期还款成功，当前账户余额：" + currentBalance + "元");
    }
    @Override
    public Result<List<RepaymentPlanListVO>> queryAllRepaymentPlans() {
        List<RepaymentPlanListVO> planList = repaymentPlanMapper.selectAllPlansWithUserInfo();
        if (planList == null || planList.isEmpty()) {
            throw new BusinessException(400, "暂无还款计划");
        }

        for (RepaymentPlanListVO plan : planList) {
            if (plan.getOverdueCount() != null && plan.getOverdueCount() > 0) {
                plan.setHasOverdue(true);
            } else {
                plan.setHasOverdue(false);
            }
            // 避免前端拿到 null
            if (plan.getPaidAmount() == null) plan.setPaidAmount(BigDecimal.ZERO);
            if (plan.getUnpaidAmount() == null) plan.setUnpaidAmount(BigDecimal.ZERO);
            if (plan.getOverdueAmount() == null) plan.setOverdueAmount(BigDecimal.ZERO);
        }

        return Result.success(planList);
    }

    // ========== 立即还款 ==========

    @Override
    public Result<DueItemsVO> queryDueItems(String userId) {
        List<DueItemVO> items = monthlyRepaymentMapper.selectDueItemsByUserId(userId);
        if (items == null || items.isEmpty()) {
            return Result.success(new DueItemsVO(0, 0, java.util.Collections.emptyList()));
        }

        int overdueCount = (int) items.stream()
                .filter(i -> "overdue".equals(i.getStatus()))
                .count();

        // 计算 totalDue = 当期应还 + 罚息
        // 罚息由 OverdueCheckService 每日累加到 monthly_repayment.penalty_amount，
        // SQL 里已映射到 overduePenalty（未逾期/存量记录该字段为 0）。
        for (DueItemVO item : items) {
            BigDecimal penalty = item.getOverduePenalty() != null
                    ? item.getOverduePenalty()
                    : BigDecimal.ZERO;
            BigDecimal amount = item.getRepaymentAmount() != null
                    ? item.getRepaymentAmount()
                    : BigDecimal.ZERO;
            item.setTotalDue(amount.add(penalty));
        }

        DueItemsVO result = new DueItemsVO(items.size(), overdueCount, items);
        return Result.success(result);
    }
}