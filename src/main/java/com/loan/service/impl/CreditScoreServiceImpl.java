package com.loan.service.impl;

import com.loan.entity.UserCreditScore;
import com.loan.entity.vo.Result;
import com.loan.exception.BusinessException;
import com.loan.mapper.UserCreditScoreMapper;
import com.loan.service.CreditScoreService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Service
public class CreditScoreServiceImpl implements CreditScoreService {

    @Resource
    private UserCreditScoreMapper userCreditScoreMapper;

    @Override
    public Result<?> getCreditScore(String userId) {
        UserCreditScore creditScore = userCreditScoreMapper.selectByUserId(userId);

        if (creditScore == null) {
            // 如果不存在，创建一个默认值
            creditScore = new UserCreditScore();
            creditScore.setUserId(userId);
            creditScore.setCreditScore(650); // 默认信用分
            userCreditScoreMapper.insert(creditScore);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("userId", userId);
        data.put("creditScore", creditScore.getCreditScore());
        data.put("creditLevel", getCreditLevel(creditScore.getCreditScore()));


        return Result.success(data);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> updateCreditScore(String userId, Integer creditScore) {
        if (creditScore == null || creditScore < 300 || creditScore > 950) {
            throw new BusinessException(400, "信用分范围为300-950");
        }

        // 使用 upsert，存在则更新，不存在则插入
        UserCreditScore userCreditScore = new UserCreditScore();
        userCreditScore.setUserId(userId);
        userCreditScore.setCreditScore(creditScore);

        int rows = userCreditScoreMapper.upsert(userCreditScore);
        if (rows < 1) {
            throw new BusinessException(500, "信用分更新失败");
        }

        return Result.success(new java.util.HashMap<String, Object>() {{
            put("message", "信用分设置成功");
            put("creditScore", creditScore);
            put("creditLevel", getCreditLevel(creditScore));
        }});
    }

    private String getCreditLevel(Integer score) {
        if (score == null) {
            return "未设置";
        } else if (score >= 750) {
            return "优秀";
        } else if (score >= 650) {
            return "良好";
        } else if (score >= 600) {
            return "一般";
        } else {
            return "较差";
        }
    }
}
