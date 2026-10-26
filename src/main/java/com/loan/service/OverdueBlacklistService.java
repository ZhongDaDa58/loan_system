package com.loan.service;

import com.loan.entity.OverdueBlacklist;

import java.util.Date;
import java.util.List;

/**
 * 逾期黑名单服务（逾期处理功能升级 第 6 步）
 */
public interface OverdueBlacklistService {

    /**
     * 把用户加入黑名单。
     *
     * @param userId            用户 ID
     * @param reason            原因（必填，会展示给审核员）
     * @param overdueLevel      触发时的逾期等级，可空
     * @param relatedRepaymentId 触发的逾期期次，可空
     * @param expireAt          到期时间，{@code null} 表示永久
     * @param operatorName      操作人，系统自动加入时传 null 或 "SYSTEM"
     * @return {@code true}=新加入；{@code false}=该用户已有生效中的记录，本次未重复加
     */
    boolean add(String userId, String reason, String overdueLevel,
                String relatedRepaymentId, Date expireAt, String operatorName);

    /**
     * 解除该用户所有生效中的黑名单记录。
     *
     * @return 被解除的条数（0 表示本来就不在黑名单里）
     */
    int release(String userId, String reason, String operatorName);

    /**
     * 是否处于生效中的黑名单（未解除 + 未过期）。
     */
    boolean isBlacklisted(String userId);

    /**
     * 取生效中的记录，用于给审核员展示"为什么被拦"。没有则返回 {@code null}。
     */
    OverdueBlacklist getActive(String userId);

    /**
     * 分页查生效中的黑名单。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数（内部会限制上限，防止拉爆）
     */
    List<OverdueBlacklist> listActive(int page, int size);

    /** 生效中的总数 */
    long countActive();
}
