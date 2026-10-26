package com.loan.entity;

import lombok.Data;

import java.util.Date;

/**
 * 催收记录 —— 每次跟进留痕（逾期处理功能升级 第 6 步-c）
 *
 * <p>一条记录 = 一次联系。用途不只是"留个档"，还支撑两件事：
 * <ul>
 *   <li>追溯"承诺过几次、兑现几次"（{@code promised_pay_date} 的流水）</li>
 *   <li>合规审计 —— 催收行为必须有记录，这是监管要求</li>
 * </ul>
 *
 * <p>{@code method} / {@code result} 用 String 而不是枚举：
 * 催收手段（微信、上门、法务）以后还会加，ENUM 加值要 ALTER TABLE。
 * 合法取值约束在 {@link com.loan.service.CollectionRecordService} 层。
 */
@Data
public class CollectionRecord {

    private Long id;

    /** 业务 ID */
    private String recordId;

    /** 所属催收任务 */
    private String taskId;

    /** 欠款用户（冗余，便于按用户查全量记录） */
    private String userId;

    /** 联系方式：phone / sms / visit / other */
    private String method;

    /** 结果：connected / no_answer / refused / promised / paid / wrong_number */
    private String result;

    /** 本次承诺的还款日（仅 result=promised 时有意义） */
    private Date promisedPayDate;

    /** 备注（客户说了什么） */
    private String remark;

    /** 催收员 */
    private String operatorName;

    private Date createTime;
}
