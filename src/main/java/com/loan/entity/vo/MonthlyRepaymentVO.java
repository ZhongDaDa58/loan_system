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

    /**
     * 当期罚息（逾期后每天累加）。
     *
     * <p>做进这个 VO 是为了让「还款计划详情」页也能看到罚息 ——
     * 之前只有「待还项」接口（{@code /due-items}）带罚息，
     * 详情页拿到的是不含罚息的金额，用户在那里还款会被后端判"金额不一致"。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "0.00")
    private BigDecimal penaltyAmount;

    /**
     * 当期应还总额 = repaymentAmount + penaltyAmount。
     *
     * <p>前端展示和提交都应该用这个字段，<b>不要自己加</b> —— 否则两处算法一旦不一致，
     * 用户就会遇到"看到 X 元、提交 X 元却报金额不一致"。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "0.00")
    private BigDecimal totalDue;

    private Date dueDate;           // 到期还款日
    private String repaymentStatus; // 还款状态
    private String repaymentStatusDesc; // 状态描述
    private Date repaymentTime;     // 实际还款时间
}
