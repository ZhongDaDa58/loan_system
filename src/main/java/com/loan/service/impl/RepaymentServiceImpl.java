package com.loan.service.impl;
import com.loan.entity.MonthlyRepayment;
import com.loan.entity.UserBankCard;
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

        if (!monthlyRepayment.getRepaymentAmount().equals(submitAmount)) {
            System.out.println("submitAmount: " + submitAmount);
            System.out.println("monthlyRepayment.getRepaymentAmount(): " + monthlyRepayment.getRepaymentAmount());
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
        }

        return Result.success(planList);
    }
}