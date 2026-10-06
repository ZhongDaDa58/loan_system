package com.loan.service.impl;

import com.loan.entity.ShadowScoreLog;
import com.loan.mapper.ShadowScoreLogMapper;
import com.loan.service.ShadowScoreService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 影子评分服务实现（W4 · 主线 A6）
 *
 * 设计要点：
 *  1) **异步**：记录写入在独立线程执行，绝不阻塞评分接口；
 *  2) **零依赖**：Java 侧不引入 XGBoost native 依赖，影子分数由离线批量回填
 *     （shadow_status: PENDING → DONE），保证"不阻塞 + 不增部署负担"；
 *  3) **异常隔离**：记录失败只告警，不影响主流程。
 */
@Service
public class ShadowScoreServiceImpl implements ShadowScoreService {

    private static final Logger log = LoggerFactory.getLogger(ShadowScoreServiceImpl.class);

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "shadow-score-recorder");
        t.setDaemon(true);
        return t;
    });

    @Resource
    private ShadowScoreLogMapper shadowScoreLogMapper;

    @Override
    public void recordAsync(ShadowScoreLog record) {
        if (record == null) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    if (record.getShadowStatus() == null) {
                        record.setShadowStatus("PENDING");
                    }
                    if (record.getCreatedAt() == null) {
                        record.setCreatedAt(new Date());
                    }
                    shadowScoreLogMapper.insert(record);
                    log.info("🕶️ 影子记录已落库: id={}, 主模型={}, 主分={}, 用户={}",
                            record.getId(), record.getPrimaryModelKey(), record.getPrimaryScore(), record.getUserId());
                } catch (Exception e) {
                    log.warn("⚠️ 影子记录落库失败（不影响主流程）: {}", e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("⚠️ 影子任务提交失败（不影响主流程）: {}", e.getMessage());
        }
    }

    @Override
    public List<ShadowScoreLog> listPending(int limit) {
        return shadowScoreLogMapper.selectByStatus("PENDING", limit);
    }

    @Override
    public List<ShadowScoreLog> listRecent(int limit) {
        return shadowScoreLogMapper.selectRecent(limit);
    }

    @Override
    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> rows = shadowScoreLogMapper.countByStatus();
        int total = 0;
        for (Map<String, Object> row : rows) {
            Object cnt = row.get("cnt");
            int value = cnt instanceof Number n ? n.intValue() : 0;
            total += value;
            result.put(String.valueOf(row.get("shadowStatus")), value);
        }
        result.put("total", total);
        return result;
    }

    @Override
    public boolean fillShadowResult(Long id, String shadowModelKey, Integer shadowScore,
                                    String shadowDecision, String note) {
        try {
            ShadowScoreLog record = new ShadowScoreLog();
            record.setId(id);
            record.setShadowStatus("DONE");
            record.setShadowModelKey(shadowModelKey);
            record.setShadowScore(shadowScore);
            record.setShadowDecision(shadowDecision);
            record.setShadowNote(note);
            record.setScoredAt(new Date());
            return shadowScoreLogMapper.updateShadowResult(record) > 0;
        } catch (Exception e) {
            log.warn("⚠️ 影子分数回填失败: id={}, reason={}", id, e.getMessage());
            return false;
        }
    }
}
