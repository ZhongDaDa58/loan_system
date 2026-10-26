package com.loan.entity.vo.stats;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 逾期分级看板（逾期处理功能升级 第 6 步）
 *
 * <p>原有的 {@link OverdueStatsVO} 只给"逾期总额/客户数/逾期率"，看不出**有多严重**。
 * 这里补上两个维度：
 * <ul>
 *   <li><b>分级分布</b>：M1/M2/M3 各多少笔、多少钱、占比 —— 这是准确的</li>
 *   <li><b>迁徙情况</b>：期内有多少笔从 M1 升到 M2、M2 升到 M3</li>
 * </ul>
 *
 * <p><b>⚠️ 关于迁徙率的口径</b>：严谨的迁徙率需要"历史等级快照"（每期初各等级存量），
 * 本项目没有建快照表，只有 {@code overdue_level_changed_at}（最近一次变更时间）。
 * 所以这里的率是<b>近似值</b>，口径见 {@link Migration#getCaliberNote()}，
 * 不要拿去和同业报表直接对比。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OverdueLevelStatsVO {

    /** 逾期总笔数（M1+M2+M3） */
    private Integer totalCount;

    /** 逾期总金额（含罚息） */
    private BigDecimal totalAmount;

    /** 分级分布，按 M1 -> M2 -> M3 顺序 */
    private List<LevelBucket> distribution;

    /** 迁徙情况 */
    private Migration migration;

    /**
     * 单个等级的分布
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LevelBucket {
        /** M1 / M2 / M3 */
        private String level;
        /** 展示用标签，如 "M1（1-30天）" */
        private String label;
        /** 笔数 */
        private Integer count;
        /** 金额（当期应还 + 罚息） */
        private BigDecimal amount;
        /** 笔数占比（%），保留 1 位小数 */
        private BigDecimal countRatio;
    }

    /**
     * 迁徙情况
     *
     * <p><b>事件数是准确的</b>（期内确实有 N 笔升级到 M2/M3）；
     * <b>率是近似的</b>（分母用"当前存量 + 期内升走量"倒推期初规模）。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Migration {
        /** 期内由 M1 升级到 M2 的笔数 */
        private Integer m1ToM2Count;
        /** 期内由 M2 升级到 M3 的笔数 */
        private Integer m2ToM3Count;
        /** M1 -> M2 近似迁徙率（%） */
        private BigDecimal m1ToM2Rate;
        /** M2 -> M3 近似迁徙率（%） */
        private BigDecimal m2ToM3Rate;
        /** 口径说明（直接返回给前端展示，避免被当成精确值使用） */
        private String caliberNote;
    }
}
