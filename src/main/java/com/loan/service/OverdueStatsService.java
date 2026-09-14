package com.loan.service;

import com.loan.entity.vo.Result;
import com.loan.entity.vo.stats.OverdueStatsVO;

public interface OverdueStatsService {

    Result<OverdueStatsVO> getStats(String startDate, String endDate);
}
