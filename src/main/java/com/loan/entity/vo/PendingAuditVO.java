package com.loan.entity.vo;
import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;

@Data
public class PendingAuditVO {
    private String applicationId;   // 申请 ID
    private String userId;          // 用户 ID ⭐ 新增
    private String userName;        // 申请人姓名
    private String userPhone;       // 申请人手机号
    private String productName;     // 产品名称
    private BigDecimal applyAmount; // 申请金额
    private Integer applyTerm;      // 申请期限
    private Integer creditScore;    // 信用分
    private Date applyTime;         // 申请时间
}