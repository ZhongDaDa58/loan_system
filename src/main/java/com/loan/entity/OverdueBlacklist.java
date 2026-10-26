package com.loan.entity;

import lombok.Data;

import java.util.Date;

/**
 * 逾期黑名单（逾期处理功能升级 第 6 步）
 *
 * <p>和"设备黑名单"是<b>两个维度</b>：设备黑名单管的是"这台设备有问题"（模拟器、多账号、骗贷团伙），
 * 逾期黑名单管的是"这个用户有严重逾期历史"。同一个用户可能换设备继续申请，
 * 同一台设备也可能被清白用户使用 —— 混在一起会误杀。
 *
 * <p><b>过期语义</b>：{@code status} 只表示"是否被解除"，
 * 过期靠 {@code expire_at} 判断（{@code NULL} = 永久）。
 * 所以判断"是否在黑名单里"要用 {@code status='active' AND (expire_at IS NULL OR expire_at > NOW())}，
 * 不要只看 status。
 *
 * @see com.loan.mapper.OverdueBlacklistMapper
 */
@Data
public class OverdueBlacklist {

    private Long id;

    /** 被拉黑的用户 ID */
    private String userId;

    /** 加入原因（展示给审核员，要说人话） */
    private String reason;

    /** 触发时的逾期等级 M1/M2/M3 */
    private String overdueLevel;

    /** 触发的逾期期次 ID —— 出了问题能追溯是哪一期 */
    private String relatedRepaymentId;

    /** active=生效中 / released=已解除 */
    private String status;

    /** 生效时间 */
    private Date effectiveAt;

    /** 到期时间，NULL 表示永久 */
    private Date expireAt;

    /** 解除时间 */
    private Date releasedAt;

    /** 解除原因 */
    private String releaseReason;

    /** 操作人（人工加入/解除时记录） */
    private String operatorName;

    private Date createTime;

    private Date updateTime;
}
