package com.loan.entity.vo;
import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;

@Data
public class LoanApplicationVO {
    private String applicationId;   // 申请 ID
    private String userId;          // 用户 ID ⭐ 新增
    private String userName;        // 申请人姓名
    private String userPhone;       // 申请人手机号
    private String productName;     // 产品名称
    private BigDecimal applyAmount; // 申请金额
    private Integer applyTerm;      // 申请期限
    private String applicationStatus; // 申请状态
    private String statusDesc;      // 状态描述（如：待审核）
    private Date applyTime;         // 申请时间
    /**
     * 合同状态: 0-未生成, 1-待签署, 2-已签署, 3-已生效
     */
    private Integer contractStatus;

    /**
     * 合同PDF访问路径 (如: /contracts/draft_xxx.pdf)
     */
    private String contractPath;

    /**
     * 用户签署时间
     */
    private Date signTime;

    /**
     * 平台盖章时间
     */
    private Date stampTime;
}