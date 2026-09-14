package com.loan.service;

import com.loan.entity.vo.LoanOverviewVO;
import com.loan.entity.vo.Result;

public interface LoanOverviewService {

    Result<LoanOverviewVO> getLoanOverview(String userId);
}
