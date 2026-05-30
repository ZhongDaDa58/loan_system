package com.loan.util;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 还款计算工具类（等额本息/等额本金）
 */
public class RepaymentCalculationUtil {
    private static final int SCALE = 2; // 金额保留2位小数
    private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP; // 四舍五入

    /**
     * 等额本息计算：每月还款金额固定
     * 公式：每月还款额 = 本金 × 月利率 × (1+月利率)^期数 / [(1+月利率)^期数 - 1]
     * @param principal 本金
     * @param annualRate 年利率（如8.5=8.5%）
     * @param term 期数（月）
     * @return 每月还款明细（本金+利息）
     */
    public static List<MonthlyRepaymentDetail> calculateEqualPrincipalInterest(BigDecimal principal, BigDecimal annualRate, int term) {
        List<MonthlyRepaymentDetail> detailList = new ArrayList<>();
        BigDecimal monthlyRate = annualRate.divide(new BigDecimal("12"), 6, ROUNDING_MODE).divide(new BigDecimal("100"), 6, ROUNDING_MODE); // 月利率（保留6位小数）
        // 替换原第25行代码：
        BigDecimal base = BigDecimal.ONE.add(monthlyRate);
        BigDecimal pow = base.pow(term, new java.math.MathContext(10, ROUNDING_MODE)); // 使用MathContext指定精度和舍入模式

        BigDecimal monthlyAmount = principal.multiply(monthlyRate).multiply(pow).divide(pow.subtract(BigDecimal.ONE), SCALE, ROUNDING_MODE); // 每月还款额

        BigDecimal remainingPrincipal = principal; // 剩余本金
        for (int i = 1; i <= term; i++) {
            BigDecimal interest = remainingPrincipal.multiply(monthlyRate).setScale(SCALE, ROUNDING_MODE); // 当期利息
            BigDecimal principalOfMonth = monthlyAmount.subtract(interest).setScale(SCALE, ROUNDING_MODE); // 当期本金
            // 最后一期调整（避免总金额偏差）
            if (i == term) {
                principalOfMonth = remainingPrincipal.setScale(SCALE, ROUNDING_MODE);
                monthlyAmount = principalOfMonth.add(interest).setScale(SCALE, ROUNDING_MODE);
            }
            remainingPrincipal = remainingPrincipal.subtract(principalOfMonth).setScale(SCALE, ROUNDING_MODE);
            detailList.add(new MonthlyRepaymentDetail(i, principalOfMonth, interest, monthlyAmount));
        }
        return detailList;
    }

    /**
     * 等额本金计算：每月本金固定，利息递减
     * 公式：每月还款额 = 每月本金 + 当期利息；每月本金 = 总本金 / 期数；当期利息 = 剩余本金 × 月利率
     */
    public static List<MonthlyRepaymentDetail> calculateEqualPrincipal(BigDecimal principal, BigDecimal annualRate, int term) {
        List<MonthlyRepaymentDetail> detailList = new ArrayList<>();
        BigDecimal monthlyRate = annualRate.divide(new BigDecimal("12"), 6, ROUNDING_MODE).divide(new BigDecimal("100"), 6, ROUNDING_MODE);
        BigDecimal monthlyPrincipal = principal.divide(new BigDecimal(term), SCALE, ROUNDING_MODE); // 每月固定本金
        BigDecimal remainingPrincipal = principal;

        for (int i = 1; i <= term; i++) {
            BigDecimal interest = remainingPrincipal.multiply(monthlyRate).setScale(SCALE, ROUNDING_MODE); // 当期利息
            BigDecimal monthlyAmount = monthlyPrincipal.add(interest).setScale(SCALE, ROUNDING_MODE); // 当期还款额
            // 最后一期调整
            if (i == term) {
                monthlyPrincipal = remainingPrincipal.setScale(SCALE, ROUNDING_MODE);
                monthlyAmount = monthlyPrincipal.add(interest).setScale(SCALE, ROUNDING_MODE);
            }
            remainingPrincipal = remainingPrincipal.subtract(monthlyPrincipal).setScale(SCALE, ROUNDING_MODE);
            detailList.add(new MonthlyRepaymentDetail(i, monthlyPrincipal, interest, monthlyAmount));
        }
        return detailList;
    }

    /**
     * 每月还款明细DTO
     */
    public static class MonthlyRepaymentDetail {
        private int term; // 期数
        private BigDecimal principal; // 当期本金
        private BigDecimal interest; // 当期利息
        private BigDecimal repaymentAmount; // 当期还款金额

        public MonthlyRepaymentDetail(int term, BigDecimal principal, BigDecimal interest, BigDecimal repaymentAmount) {
            this.term = term;
            this.principal = principal;
            this.interest = interest;
            this.repaymentAmount = repaymentAmount;
        }

        // getter
        public int getTerm() { return term; }
        public BigDecimal getPrincipal() { return principal; }
        public BigDecimal getInterest() { return interest; }
        public BigDecimal getRepaymentAmount() { return repaymentAmount; }
    }
}