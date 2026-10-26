package com.loan.mapper;

import com.loan.entity.PenaltyWaiver;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

/**
 * 罚息减免 Mapper（逾期处理功能升级 第 6 步-d）
 *
 * <p><b>审批相关的三个方法都用条件 UPDATE</b>（{@code WHERE status='pending'}）：
 * 两个人同时点"通过"时只有一个返回 1，另一个返回 0 视为"已被处理"。
 * 不这么做就会把罚息减两次，而罚息是钱。
 */
@Mapper
public interface PenaltyWaiverMapper {

    int insert(PenaltyWaiver waiver);

    PenaltyWaiver selectByWaiverId(@Param("waiverId") String waiverId);

    /**
     * 该期次当前是否有待审批的申请（防重复申请 —— 两条都通过会减两次罚息）。
     */
    int countPendingByRepaymentId(@Param("repaymentId") String repaymentId);

    /**
     * 抢占审批权并置为通过。
     *
     * @return 1=抢到；0=已被别人处理过（或不存在）
     */
    int markApproved(@Param("waiverId") String waiverId,
                     @Param("approver") String approver,
                     @Param("remark") String remark);

    /**
     * 抢占审批权并置为驳回。
     *
     * @return 1=抢到；0=已被别人处理过（或不存在）
     */
    int markRejected(@Param("waiverId") String waiverId,
                     @Param("approver") String approver,
                     @Param("remark") String remark);

    /**
     * 撤销（申请人自己撤回）。
     *
     * @return 1=撤到；0=已被处理过
     */
    int markCancelled(@Param("waiverId") String waiverId,
                      @Param("applicant") String applicant,
                      @Param("remark") String remark);

    /**
     * 分页查申请单。
     *
     * @param status 状态过滤，null=不限
     */
    List<PenaltyWaiver> selectPage(@Param("status") String status,
                                   @Param("userId") String userId,
                                   @Param("offset") int offset,
                                   @Param("limit") int limit);

    long countPage(@Param("status") String status, @Param("userId") String userId);

    // ===== 罚息余额操作（只在本服务里用，不对 Controller 暴露）=====

    /** 取某期次当前罚息余额，用于校验"减免不能超过实际罚息" */
    BigDecimal selectPenaltyAmount(@Param("repaymentId") String repaymentId);

    /**
     * 减少罚息余额（审批通过时调用）。
     *
     * <p>条件里带 {@code penalty_amount >= amount} —— 即使校验和扣减之间
     * 罚息因为别的原因变小了，也不会扣成负数。
     *
     * @return 1=扣成功；0=余额不足
     */
    int decreasePenalty(@Param("repaymentId") String repaymentId,
                        @Param("amount") BigDecimal amount);
}
