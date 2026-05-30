package com.loan.util;

import com.loan.entity.enums.ScorecardResultEnum;

public class RiskControlUtil {

    public static boolean preCheck(int creditScore) {
        return creditScore >= 600;
    }

    public static ScorecardResultEnum evaluateScorecard(int creditScore, Integer autoPassScore,
                                                        Integer manualReviewMinScore) {
        if (creditScore >= autoPassScore) {
            return ScorecardResultEnum.AUTO_PASS;
        } else if (creditScore >= manualReviewMinScore) {
            return ScorecardResultEnum.MANUAL_REVIEW;
        } else {
            return ScorecardResultEnum.AUTO_REJECT;
        }
    }
}
