package com.loan.entity.vo;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;

@Data
public class MonthlyRepaymentVO {
    private String repaymentId;     // 还款明细ID
    private Integer term;           // 期数
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "0.00")
    private BigDecimal principal;   // 当期本金

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "0.00")
    private BigDecimal interest;    // 当期利息

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "0.00")
    private BigDecimal repaymentAmount; // 当期还款金额

    private Date dueDate;           // 到期还款日
    private String repaymentStatus; // 还款状态
    private String repaymentStatusDesc; // 状态描述
    private Date repaymentTime;     // 实际还款时间
}
