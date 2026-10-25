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

    // ==================== 逾期扣分（逾期处理功能升级 第 5 步）====================

    /** 信用分下限：扣到底就不再扣（避免负分/0 分这种不合业务常识的值） */
    public static final int MIN_CREDIT_SCORE = 300;

    /** 各等级扣分 */
    private static final java.util.Map<String, Integer> OVERDUE_DEDUCT = java.util.Map.of(
            "M1", 10,
            "M2", 20,
            "M3", 50);

    @Override
    public int deductForOverdue(String userId, String level) {
        Integer deduct = OVERDUE_DEDUCT.get(level);
        if (deduct == null) {
            log.warn("逾期扣分跳过：未知等级 level={}, userId={}", level, userId);
            return 0;
        }

        UserCreditScore existing = userCreditScoreMapper.selectByUserId(userId);
        if (existing == null || existing.getCreditScore() == null) {
            // 没有信用分记录就不扣 —— 不在这里「凭空造一条」，那是建档流程的职责
            log.warn("逾期扣分跳过：用户 {} 无信用分记录", userId);
            return 0;
        }

        int oldScore = existing.getCreditScore();
        int newScore = Math.max(MIN_CREDIT_SCORE, oldScore - deduct);
        int actual = oldScore - newScore;
        if (actual <= 0) {
            log.info("逾期扣分跳过：用户 {} 信用分已到底（{}），不再扣", userId, oldScore);
            return 0;
        }

        userCreditScoreMapper.updateCreditScore(userId, newScore);
        recordCreditScoreChange(userId, oldScore, newScore, "逾期扣分-" + level);
        log.info("⚠️ 逾期扣分：用户 {} 等级 {}，信用分 {} -> {}（-{}）",
                userId, level, oldScore, newScore, actual);
        return actual;
    }
}