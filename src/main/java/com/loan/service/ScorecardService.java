package com.loan.service;

import com.loan.entity.dto.UserProfileDTO;
import com.loan.entity.vo.ScorecardResultVO;

public interface ScorecardService {

    ScorecardResultVO calculateScore(UserProfileDTO userProfile);

    Integer calculateScoreOnly(UserProfileDTO userProfile);
}