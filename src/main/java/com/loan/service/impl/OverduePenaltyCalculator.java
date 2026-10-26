package com.loan.service.impl;

import com.loan.entity.MonthlyRepayment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * 逾期罚息计算器（逾期处理功能升级 第 3 步；第 8 步做合规调整）
 *
 * <p><b>为什么单独一个类</b>：罚息有自己的配置（费率、倍数、封顶）和一套容易算错的口径
 * （幂等、不追补历史、年化封顶）。塞在 OverdueCheckService 里会让那个类同时承担
 * "扫描分级"和"算钱"两件事，而且那个类已经接近 300 行。
 *
 * <h3>计算口径</h3>
 * <pre>
 *   本次新增罚息 = 当期应还 × 日罚息率 × 等级倍数 × 距上次计算天数（单利，不复利）
 *   累计上限     = 当期应还 × capAnnualRate × 逾期年数
 * </pre>
 *
 * <p><b>三条容易踩的规则</b>：
 * <ol>
 *   <li><b>幂等</b>：靠 {@code penalty_last_calc_date}，同一天第二次调用返回 0</li>
 *   <li><b>首次只算 1 天</b>：存量逾期（可能已逾期 70 天）如果按天追补，会突然多出一大笔欠款</li>
 *   <li><b>年化封顶</b>：即使有人把日费率或倍数配大了，累计罚息也不会超过上限</li>
 * </ol>
 *
 * <p>⚠️ 关于等级倍数：第 8 步合规调整后默认全部为 1，不再 M1/M2/M3 递增 ——
 * 按天计息本身已经实现了"越拖越贵"，再叠倍数等于对同一件事罚两次。
 * 配置项保留了，方便按实际业务口径调整。
 */
@Component
public class OverduePenaltyCalculator {

    private static final Logger log = LoggerFactory.getLogger(OverduePenaltyCalculator.class);

    @Value("${overdue.penalty.daily-rate:0.00016}")
    private BigDecimal penaltyDailyRate;

    @Value("${overdue.penalty.multiplier.m1:1}")
    private BigDecimal penaltyMultiplierM1;

    @Value("${overdue.penalty.multiplier.m2:1}")
    private BigDecimal penaltyMultiplierM2;

    @Value("${overdue.penalty.multiplier.m3:1}")
    private BigDecimal penaltyMultiplierM3;

    /**
     * 罚息累计年化上限（合规硬封顶）。
     *
     * <p>默认 0.06（6%/年）—— 配合假设的 18% 合同利率把客户总成本压在约 24% 以内。
     * 口径见 application.properties 里的合规调整记录。
     */
    @Value("${overdue.penalty.cap-annual-rate:0.06}")
    private BigDecimal penaltyCapAnnualRate;

    /**
     * 计算本次应新增的罚息。
     *
     * @param r                  还款记录（读 {@code repaymentAmount} / {@code penaltyAmount} /
     *                           {@code penaltyLastCalcDate}）
     * @param today              今天
     * @param level              当前逾期等级 M1/M2/M3
     * @param currentOverdueDays 本次算出的逾期天数。
     *                           <b>不要用 {@code r.getOverdueDays()}</b> —— 那是更新前读进内存的旧值
     * @return 本次应新增的罚息（已按分四舍五入并应用封顶）；0 表示不新增
     */
    public BigDecimal calcIncrement(MonthlyRepayment r, LocalDate today,
                                    String level, long currentOverdueDays) {
        if (r.getRepaymentAmount() == null) {
            return BigDecimal.ZERO;
        }

        LocalDate last = toLocalDate(r.getPenaltyLastCalcDate());
        long daysToCharge;
        if (last == null) {
            daysToCharge = 1;                 // 首次：只算今天（不追补历史）
        } else if (last.isBefore(today)) {
            daysToCharge = ChronoUnit.DAYS.between(last, today);
        } else {
            return BigDecimal.ZERO;            // 今天已算过（幂等）
        }

        BigDecimal raw = r.getRepaymentAmount()
                .multiply(penaltyDailyRate)
                .multiply(multiplierOf(level))
                .multiply(BigDecimal.valueOf(daysToCharge));

        return applyCap(r, raw, currentOverdueDays).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal multiplierOf(String level) {
        return switch (level) {
            case "M3" -> penaltyMultiplierM3;
            case "M2" -> penaltyMultiplierM2;
            default -> penaltyMultiplierM1;
        };
    }

    /**
     * 罚息累计封顶（合规兜底）。
     *
     * <p><b>为什么要在代码里挡一层</b>：日费率和倍数都是配置项，有人调大就可能导致
     * 罚息失控（原本 M3×3 倍算下来是年化 54.75%）。只靠"配注释提醒"不可靠。
     *
     * <p>已计罚息达到上限后返回 0（停止计息），未达到时本次增量不超过剩余额度。
     */
    private BigDecimal applyCap(MonthlyRepayment r, BigDecimal increment,
                               long currentOverdueDays) {
        if (currentOverdueDays <= 0 || penaltyCapAnnualRate == null
                || penaltyCapAnnualRate.signum() <= 0) {
            return increment;
        }

        BigDecimal years = BigDecimal.valueOf(currentOverdueDays)
                .divide(BigDecimal.valueOf(365), 10, RoundingMode.HALF_UP);
        BigDecimal cap = r.getRepaymentAmount()
                .multiply(penaltyCapAnnualRate)
                .multiply(years);
        BigDecimal already = r.getPenaltyAmount() == null
                ? BigDecimal.ZERO
                : r.getPenaltyAmount();

        BigDecimal room = cap.subtract(already);
        if (room.signum() <= 0) {
            log.debug("罚息已达封顶：repaymentId={}, 已计={}, 上限={}",
                    r.getRepaymentId(), already, cap);
            return BigDecimal.ZERO;
        }
        return increment.min(room);
    }

    private static LocalDate toLocalDate(Date date) {
        return date == null ? null
                : date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
