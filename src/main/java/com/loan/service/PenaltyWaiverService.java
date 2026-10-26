package com.loan.service;

import com.loan.entity.PenaltyWaiver;

import java.math.BigDecimal;
import java.util.List;

/**
 * 罚息减免服务（逾期处理功能升级 第 6 步-d）
 *
 * <p><b>这是改罚息的唯一合法路径。</b>项目里没有任何"直接设置罚息"的接口，
 * 就是为了让每一次减免都有单据、有审批人、有原因 —— 开发文档对这块的原话是
 * 「业务常见需求，但要走审批，别直接改数」。
 */
public interface PenaltyWaiverService {

    /**
     * 发起减免申请。
     *
     * <p>申请阶段<b>不动罚息</b>（status=pending），客户这段时间仍可正常还款 ——
     * 如果还款了，审批时余额不足会自动驳回。这样不会出现"申请减免后客户还不了钱"。
     *
     * @param repaymentId  还款期次
     * @param waivedAmount 申请减免金额（必须 &gt; 0 且不超过当前罚息）
     * @param reason       减免原因（必填）
     * @param applicant    申请人
     */
    PenaltyWaiver apply(String repaymentId, BigDecimal waivedAmount,
                        String reason, String applicant);

    /**
     * 审批通过 —— <b>这是唯一会真正减少罚息的操作</b>。
     *
     * @param approver 审批人，<b>不能与申请人相同</b>（自己批自己等于没有审批）
     * @throws com.loan.exception.BusinessException 申请单不存在 / 已被处理 / 审批人=申请人 / 罚息余额不足
     */
    PenaltyWaiver approve(String waiverId, String approver, String remark);

    /**
     * 审批驳回（罚息不变）。
     */
    PenaltyWaiver reject(String waiverId, String approver, String remark);

    /**
     * 申请人撤回（罚息不变）。
     *
     * @return false 表示已被处理或不是本人申请
     */
    boolean cancel(String waiverId, String applicant, String remark);

    PenaltyWaiver getByWaiverId(String waiverId);

    List<PenaltyWaiver> list(String status, String userId, int page, int size);

    long count(String status, String userId);
}
