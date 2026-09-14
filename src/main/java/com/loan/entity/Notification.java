package com.loan.entity;

import lombok.Data;

import java.util.Date;

/**
 * 系统通知（App 内消息）
 */
@Data
public class Notification {
    private Long id;
    private String userId;
    private String title;
    private String content;
    private String type;         // overdue / repayment_reminder / system
    private String relatedId;    // 关联业务 ID（如还款明细ID、申请ID）
    private Boolean isRead;
    private Date createTime;
}
