package com.loan.service;

import com.loan.entity.vo.Result;
import com.loan.entity.vo.stats.OverdueLevelStatsVO;
import com.loan.entity.vo.stats.OverdueStatsVO;

public interface OverdueStatsService {

    Result<OverdueStatsVO> getStats(String startDate, String endDate);

    /**
     * 逾期分级看板（第 6 步）：M1/M2/M3 分布 + 迁徙情况。
     *
     * <p>与 {@link #getStats} 的区别：getStats 回答"逾期多少、逾期率多少"，
     * 这个方法回答"逾期<b>有多严重</b>、风险<b>在往哪个方向走</b>"。
     *
     * <p>⚠️ 迁徙率是近似值，口径见返回体的 {@code migration.caliberNote}。
     *
     * @param startDate 迁徙统计起始日（含），格式 yyyy-MM-dd，可空
     * @param endDate   迁徙统计截止日（含），格式 yyyy-MM-dd，可空
     */
    Result<OverdueLevelStatsVO> getLevelStats(String startDate, String endDate);
}
