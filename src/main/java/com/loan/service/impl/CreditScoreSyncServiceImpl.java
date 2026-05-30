package com.loan.service.impl;

import com.loan.entity.UserCreditScore;
import com.loan.mapper.UserCreditScoreMapper;
import com.loan.service.CreditScoreSyncService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

@Service
public class CreditScoreSyncServiceImpl implements CreditScoreSyncService {

    private static final Logger log = LoggerFactory.getLogger(CreditScoreSyncServiceImpl.class);

    @Resource
    private UserCreditScoreMapper userCreditScoreMapper;

    @Override
    @Transactional
    public void syncScorecardToCreditScore(String userId, Integer scorecardScore, String applicationId) {
        try {
            Integer creditScore = calculateCreditScoreFromScorecard(scorecardScore);

            UserCreditScore existingScore = userCreditScoreMapper.selectByUserId(userId);

            if (existingScore == null) {
                UserCreditScore newScore = new UserCreditScore();
                newScore.setUserId(userId);
                newScore.setCreditScore(creditScore);
                newScore.setCreateTime(new Date());
                newScore.setUpdateTime(new Date());

                userCreditScoreMapper.insert(newScore);
                log.info("✅ 为用户 {} 创建信用分记录: {}", userId, creditScore);
            } else {
                Integer oldScore = existingScore.getCreditScore();
                userCreditScoreMapper.updateCreditScore(userId, creditScore);

                recordCreditScoreChange(userId, oldScore, creditScore,
                        "评分卡评估更新，申请ID: " + applicationId);

                log.info("✅ 更新用户 {} 信用分: {} -> {}", userId, oldScore, creditScore);
            }

        } catch (Exception e) {
            log.error("❌ 同步信用分失败: {}", e.getMessage(), e);
        }
    }

    @Override
    public Integer calculateCreditScoreFromScorecard(Integer scorecardScore) {
        if (scorecardScore == null) {
            return 600;
        }

        int creditScore = scorecardScore;

        creditScore = Math.max(300, Math.min(950, creditScore));

        return creditScore;
    }

    @Override
    public void recordCreditScoreChange(String userId, Integer oldScore, Integer newScore, String reason) {
        log.info("📊 信用分变化记录 - 用户: {}, 旧分数: {}, 新分数: {}, 原因: {}",
                userId, oldScore, newScore, reason);

    }
}
