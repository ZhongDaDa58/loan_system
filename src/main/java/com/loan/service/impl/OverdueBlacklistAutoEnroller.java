package com.loan.service.impl;

import com.loan.service.OverdueBlacklistService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Calendar;
import java.util.Date;

/**
 * M3 逾期自动加入黑名单（逾期处理功能升级）
 *
 * <p><b>为什么需要</b>：黑名单在之前是**纯人工**的 —— 严重逾期的客户只要把钱还清，
 * 立刻就能再借。现实中 M3（超过 60 天）的客户即使结清也该有一段观察期。
 * 靠人工每天盯着名单去加，实际没人会这么做。
 *
 * <p><b>为什么独立成类</b>：`OverdueCheckService` 已经 286 行，而且它的职责是
 * "扫天数、定等级、计罚息"，"要不要拉黑"是风控策略，跟扫描是两个域。
 *
 * <h3>三条设计规则</h3>
 * <ol>
 *   <li><b>只处理 M3</b>：M1/M2 不拉黑（M2 已经在准入层拦着了，够用）</li>
 *   <li><b>观察期默认 6 个月</b>（可配）。不用永久 —— 太狠；也不能太短，否则等于没有</li>
 *   <li><b>还款<b>不</b>自动解除</b>：这正是"观察期"的意义。要提前解除得人工操作
 *       （{@code POST /admin/overdue-blacklist/{userId}/release}）</li>
 * </ol>
 *
 * <p><b>幂等</b>靠 {@code OverdueBlacklistService.add} 内部的 {@code insertIfAbsent}：
 * 已有生效中的记录（无论是自动加的、还是人工加的）就返回 false，不会重复插入，
 * 也<b>不会覆盖</b>人工加的那条（原因/期限保持原样）。
 *
 * <p>⚠️ 注意每天扫描都会对 M3 用户调用一次，所以这里必须幂等 —— 否则一天一条记录。
 */
@Component
public class OverdueBlacklistAutoEnroller {

    private static final Logger log = LoggerFactory.getLogger(OverdueBlacklistAutoEnroller.class);

    /** 自动拉黑的操作人标识（和人工操作区分开，便于审计） */
    private static final String SYSTEM_OPERATOR = "SYSTEM_AUTO_M3";

    /**
     * M3 自动拉黑的观察期（月）。
     *
     * <p>{@code 0} 或负数表示<b>永久</b>（相当于手动加黑名单不填到期日）。
     * 默认为 6 个月。
     */
    @Value("${overdue.blacklist.m3-expire-months:6}")
    private int m3ExpireMonths;

    @Resource
    private OverdueBlacklistService overdueBlacklistService;

    /**
     * 如果用户是 M3，把他加入黑名单。
     *
     * @param userId      欠款用户
     * @param repaymentId 触发 M3 的那一期（可为 null，仅用于追溯）
     * @return true = 本次新增；false = 本来就已有生效记录（幂等命中）
     */
    public boolean enrollIfM3(String userId, String repaymentId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }

        String reason = "逾期已达 M3（超过 60 天），系统自动加入观察期"
                + (m3ExpireMonths > 0 ? m3ExpireMonths + " 个月" : "（长期）")
                + "；结清后仍需人工解除才能再次申请";

        try {
            boolean added = overdueBlacklistService.add(
                    userId, reason, "M3", repaymentId, expireAt(), SYSTEM_OPERATOR);
            if (added) {
                log.warn("⚠️ M3 逾期自动拉黑：userId={}, repaymentId={}, 观察期={} 个月",
                        userId, repaymentId, m3ExpireMonths);
            }
            return added;
        } catch (Exception e) {
            // 拉黑失败不能影响逾期标记 / 罚息 / 扣分 —— 那些才是主链路
            log.error("M3 自动拉黑失败（不影响逾期主流程）: userId={}, repaymentId={}",
                    userId, repaymentId, e);
            return false;
        }
    }

    /** 计算到期时间；{@code m3ExpireMonths <= 0} 时返回 null（表示永久） */
    private Date expireAt() {
        if (m3ExpireMonths <= 0) {
            return null;
        }
        Calendar c = Calendar.getInstance();
        c.add(Calendar.MONTH, m3ExpireMonths);
        return c.getTime();
    }
}
