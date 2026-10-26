package com.loan.service.impl;

import com.loan.entity.OverdueBlacklist;
import com.loan.exception.BusinessException;
import com.loan.mapper.OverdueBlacklistMapper;
import com.loan.service.OverdueBlacklistService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

/**
 * 逾期黑名单服务实现（逾期处理功能升级 第 6 步）
 */
@Service
public class OverdueBlacklistServiceImpl implements OverdueBlacklistService {

    private static final Logger log = LoggerFactory.getLogger(OverdueBlacklistServiceImpl.class);

    /** 每页最多返回多少条 —— 防止把 size 传成 100000 拉爆内存 */
    private static final int MAX_PAGE_SIZE = 200;

    @Resource
    private OverdueBlacklistMapper overdueBlacklistMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean add(String userId, String reason, String overdueLevel,
                       String relatedRepaymentId, Date expireAt, String operatorName) {
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(400, "用户 ID 不能为空");
        }
        if (reason == null || reason.isBlank()) {
            // 不给"原因"的话，审核员看到拦截提示也不知道为什么拦
            throw new BusinessException(400, "加入黑名单必须填写原因");
        }

        OverdueBlacklist record = new OverdueBlacklist();
        record.setUserId(userId);
        record.setReason(reason);
        record.setOverdueLevel(overdueLevel);
        record.setRelatedRepaymentId(relatedRepaymentId);
        record.setEffectiveAt(new Date());
        record.setExpireAt(expireAt);
        record.setOperatorName(operatorName);

        int affected = overdueBlacklistMapper.insertIfAbsent(record);
        if (affected == 0) {
            // 已有生效记录 —— 不是错误，只是不用重复加（幂等）
            log.info("用户 {} 已在生效中的逾期黑名单里，跳过重复加入", userId);
            return false;
        }
        log.info("⚠️ 用户 {} 加入逾期黑名单：{}（等级={}, 到期={}）",
                userId, reason, overdueLevel, expireAt == null ? "永久" : expireAt);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int release(String userId, String reason, String operatorName) {
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(400, "用户 ID 不能为空");
        }
        int affected = overdueBlacklistMapper.releaseActive(userId, reason, operatorName);
        if (affected > 0) {
            log.info("用户 {} 的逾期黑名单已解除（{} 条）：{}", userId, affected, reason);
        }
        return affected;
    }

    @Override
    public boolean isBlacklisted(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        return overdueBlacklistMapper.countActive(userId) > 0;
    }

    @Override
    public OverdueBlacklist getActive(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        return overdueBlacklistMapper.selectActiveByUserId(userId);
    }

    @Override
    public List<OverdueBlacklist> listActive(int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return overdueBlacklistMapper.selectActivePage((safePage - 1) * safeSize, safeSize);
    }

    @Override
    public long countActive() {
        return overdueBlacklistMapper.countActiveAll();
    }
}
