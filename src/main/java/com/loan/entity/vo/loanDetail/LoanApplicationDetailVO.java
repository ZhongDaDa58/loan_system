package com.loan.entity.vo.loanDetail;

import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;

@Data
public class LoanApplicationDetailVO {

    // ===== 申请基本信息 =====
    private String applicationId;       // 申请ID
    private String userId;              // 用户ID
    private String productId;           // 产品ID
    private String productName;         // 产品名称

    private BigDecimal applyAmount;     // 申请金额
    private Integer applyTerm;          // 申请期限（月）
    private String repaymentMethod;     // 还款方式（等额本息/等额本金）
    private BigDecimal annualRate;      // 年化利率
    private BigDecimal monthlyPayment;  // 每月应还

    private String loanPurpose;         // 借款用途
    private Long disbursementCardId;    // 放款银行卡ID
    private String cardBankName;        // 银行名称
    private String cardNumber;          // 银行卡号（脱敏）

    private String applicationStatus;   // 申请状态
    private String statusDesc;          // 状态描述
    private Date applyTime;             // 申请时间
    private Date updateTime;            // 更新时间

    // ===== 评分卡结果 =====
    private Integer scorecardScore;     // 评分卡分数
    private String scorecardDecision;   // 评分卡决策

    // ===== 合同信息 =====
    private Integer contractStatus;     // 合同状态
    private String contractPath;        // 合同PDF路径
    private String contractHash;        // 合同哈希值
    private Date signTime;              // 用户签署时间
    private Date stampTime;             // 平台盖章时间
}
