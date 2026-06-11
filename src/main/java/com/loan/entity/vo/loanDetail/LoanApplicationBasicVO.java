package com.loan.entity.vo.loanDetail;

import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;

@Data
public class LoanApplicationBasicVO {

    // ===== 申请标识 =====
    private String applicationId;       // 申请ID
    private String userId;              // 用户ID（用于关联，不展示）
    private String productId;           // 产品ID

    // ===== 产品信息 =====
    private String productName;         // 产品名称
    private BigDecimal interestRate;    // 年化利率
    private String repaymentMethod;     // 还款方式

    // ===== 申请内容 =====
    private BigDecimal applyAmount;     // 申请金额
    private Integer applyTerm;          // 申请期限（月）
    private BigDecimal monthlyPayment;  // 每月应还（等额本息计算）

    // ===== 放款信息 =====
    private Long disbursementCardId;    // 放款银行卡ID
    private String cardBankName;        // 银行名称
    private String cardNumber;          // 银行卡号（脱敏）

    // ===== 状态信息 =====
    private String applicationStatus;   // 申请状态
    private String statusDesc;          // 状态描述
    private Date applyTime;             // 申请时间
    private Date updateTime;            // 更新时间

    // ===== 评分卡结果 =====
    private Integer scorecardScore;     // 评分卡分数
    private String scorecardDecision;   // 评分卡决策
}
