package com.loan.service;

import com.loan.entity.vo.Result;
import com.loan.entity.vo.stats.DisbursementStatsVO;

public interface DisbursementStatsService {

    /**
     * 获取放款统计数据
     */
    Result<DisbursementStatsVO> getStats(String startDate, String endDate);
}
