package com.loan.service.impl;

import com.loan.entity.MonthlyRepayment;
import com.loan.entity.PenaltyWaiver;
import com.loan.exception.BusinessException;
import com.loan.mapper.MonthlyRepaymentMapper;
import com.loan.mapper.PenaltyWaiverMapper;
import com.loan.service.PenaltyWaiverService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 罚息减免服务实现（逾期处理功能升级 第 6 步-d）
 *
 * <p><b>审批流程的并发安全靠什么</b>：
 * <ol>
 *   <li>状态流转用条件 UPDATE（{@code WHERE status='pending'}）—— 两个人同时点"通过"，
 *       只有一个人返回 1，另一个被告知"已被处理"</li>
 *   <li>扣减罚息也用条件 UPDATE（{@code WHERE penalty_amount >= amount}）——
 *       即使中间余额变小也不会扣成负数</li>
 *   <li>两步在同一事务里 —— 任何一步失败全部回滚</li>
 * </ol>
 */
@Service
public class PenaltyWaiverServiceImpl implements PenaltyWaiverService {

    private static final Logger log = LoggerFactory.getLogger(PenaltyWaiverServiceImpl.class);

    private static final int MAX_PAGE_SIZE = 200;

    @Resource
    private PenaltyWaiverMapper penaltyWaiverMapper;

    @Resource
    private MonthlyRepaymentMapper monthlyRepaymentMapper;

    @Resource
    private com.loan.mapper.RepaymentPlanMapper repaymentPlanMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PenaltyWaiver apply(String repaymentId, BigDecimal waivedAmount,
                               String reason, String applicant) {
        if (repaymentId == null || repaymentId.isBlank()) {
            throw new BusinessException(400, "必须指定还款期次");
        }
        if (waivedAmount == null || waivedAmount.signum() <= 0) {
            throw new BusinessException(400, "减免金额必须大于 0");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(400, "必须填写减免原因");
        }
        if (applicant == null || applicant.isBlank()) {
            throw new BusinessException(400, "必须指定申请人");
        }

        MonthlyRepayment repayment = monthlyRepaymentMapper.selectById(repaymentId);
        if (repayment == null) {
            throw new BusinessException(404, "还款期次不存在");
        }
        if ("paid".equals(repayment.getRepaymentStatus())) {
            // 已还清的期次罚息已经结算，再减免没有意义
            throw new BusinessException(400, "该期已还清，无需减免罚息");
        }

        BigDecimal currentPenalty = repayment.getPenaltyAmount() == null
                ? BigDecimal.ZERO
                : repayment.getPenaltyAmount();
        if (currentPenalty.signum() <= 0) {
            throw new BusinessException(400, "该期没有罚息，无需减免");
        }
        if (waivedAmount.compareTo(currentPenalty) > 0) {
            throw new BusinessException(400,
                    "减免金额不能超过当前罚息（" + currentPenalty + " 元）");
        }

        // 一个期次同时只能有一条待审批 —— 两条都通过会把罚息减两次
        if (penaltyWaiverMapper.countPendingByRepaymentId(repaymentId) > 0) {
            throw new BusinessException(400, "该期已有待审批的减免申请，请等审批完成后再提交");
        }

        PenaltyWaiver waiver = new PenaltyWaiver();
        waiver.setWaiverId("PW-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        // MonthlyRepayment 上没有 userId，靠 plan_id 回查（罚息减免要能按客户查全量）
        waiver.setUserId(repayment.getPlanId() == null
                ? null
                : repaymentPlanMapper.selectUserIdByPlanId(repayment.getPlanId()));
        waiver.setRepaymentId(repaymentId);
        waiver.setPlanId(repayment.getPlanId());
        waiver.setWaivedAmount(waivedAmount);
        waiver.setPenaltySnapshot(currentPenalty);
        waiver.setReason(reason);
        waiver.setApplicant(applicant);
        waiver.setStatus("pending");   // SQL 里也硬编码了，这里给对象也设上（返回值要完整）
        penaltyWaiverMapper.insert(waiver);

        log.info("罚息减免申请：{} 期次 {} 申请减免 {} 元（当前罚息 {}），申请人 {}",
                waiver.getWaiverId(), repaymentId, waivedAmount, currentPenalty, applicant);
        return waiver;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PenaltyWaiver approve(String waiverId, String approver, String remark) {
        PenaltyWaiver waiver = mustGetPending(waiverId);

        // 内控红线：自己申请自己批等于没有审批
        if (approver == null || approver.isBlank()) {
            throw new BusinessException(400, "必须指定审批人");
        }
        if (approver.equals(waiver.getApplicant())) {
            throw new BusinessException(400, "审批人不能与申请人相同");
        }

        // ① 抢占审批权（条件 UPDATE，防止并发双批）
        if (penaltyWaiverMapper.markApproved(waiverId, approver, remark) == 0) {
            throw new BusinessException(400, "该申请已被处理，请刷新后重试");
        }

        // ② 扣减罚息（条件 UPDATE，余额不足返回 0 -> 事务回滚，状态回到 pending）
        int decreased = penaltyWaiverMapper.decreasePenalty(
                waiver.getRepaymentId(), waiver.getWaivedAmount());
        if (decreased == 0) {
            throw new BusinessException(400,
                    "该期罚息余额已不足 " + waiver.getWaivedAmount()
                            + " 元（可能已还款或已被其他申请减免），本次审批已撤销");
        }

        log.info("罚息减免通过：{} 期次 {} 减免 {} 元，审批人 {}",
                waiverId, waiver.getRepaymentId(), waiver.getWaivedAmount(), approver);
        return penaltyWaiverMapper.selectByWaiverId(waiverId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PenaltyWaiver reject(String waiverId, String approver, String remark) {
        PenaltyWaiver waiver = mustGetPending(waiverId);

        if (approver == null || approver.isBlank()) {
            throw new BusinessException(400, "必须指定审批人");
        }
        if (penaltyWaiverMapper.markRejected(waiverId, approver, remark) == 0) {
            throw new BusinessException(400, "该申请已被处理，请刷新后重试");
        }

        log.info("罚息减免驳回：{} 审批人 {}，原因 {}", waiverId, approver, remark);
        return penaltyWaiverMapper.selectByWaiverId(waiverId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancel(String waiverId, String applicant, String remark) {
        if (applicant == null || applicant.isBlank()) {
            throw new BusinessException(400, "必须指定操作人");
        }
        // SQL 里带 applicant = #{applicant} —— 只能撤自己提交的
        return penaltyWaiverMapper.markCancelled(waiverId, applicant, remark) > 0;
    }

    @Override
    public PenaltyWaiver getByWaiverId(String waiverId) {
        return waiverId == null ? null : penaltyWaiverMapper.selectByWaiverId(waiverId);
    }

    @Override
    public List<PenaltyWaiver> list(String status, String userId, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return penaltyWaiverMapper.selectPage(status, userId, (safePage - 1) * safeSize, safeSize);
    }

    @Override
    public long count(String status, String userId) {
        return penaltyWaiverMapper.countPage(status, userId);
    }

    /** 取一条必须处于待审批状态的申请（给友好提示，而不是让调用方看"返回 0"） */
    private PenaltyWaiver mustGetPending(String waiverId) {
        PenaltyWaiver waiver = waiverId == null ? null
                : penaltyWaiverMapper.selectByWaiverId(waiverId);
        if (waiver == null) {
            throw new BusinessException(404, "减免申请不存在");
        }
        if (!"pending".equals(waiver.getStatus())) {
            throw new BusinessException(400,
                    "该申请已是「" + waiver.getStatus() + "」状态，不能重复处理");
        }
        return waiver;
    }
}
