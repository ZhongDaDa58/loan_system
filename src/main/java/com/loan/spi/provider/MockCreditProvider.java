package com.loan.spi.provider;

import com.loan.entity.enums.SourceTypeEnum;
import com.loan.spi.DataSourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * 征信数据源 —— 模拟 Provider（W3 实现）
 *
 * 按《多源数据.md》征信源口径返回标准化字段（键名即特征名）：
 *   credit_query_cnt_3m / credit_query_cnt_6m / debt_to_income /
 *   hist_overdue_cnt_m1 / hist_overdue_cnt_m2 / hist_overdue_cnt_m3
 *
 * 生成方式：**以 userId 为种子的确定性伪随机**——同一用户每次返回一致，
 * 便于联调与复现，也避免"每次拉取数据抖动"。
 * 将来：新增 RealCreditProvider 实现同一接口并注册替换，其余代码不动。
 */
@Component
public class MockCreditProvider implements DataSourceProvider {

    private static final Logger log = LoggerFactory.getLogger(MockCreditProvider.class);

    /**
     * 数据口径版本（与《多源数据.md》/前端展示对齐）
     */
    private static final String DATA_VERSION = "v1";

    @Override
    public String sourceType() {
        return SourceTypeEnum.CREDIT.getCode();
    }

    @Override
    public Map<String, Object> fetch(String userId) {
        Map<String, Object> data = new LinkedHashMap<>();

        try {
            Random random = deterministicRandom(userId, sourceType());

            // 近 3 个月征信查询次数（多数人查询较少，存在少量高频查询者）
            int query3m = random.nextInt(100) < 70 ? random.nextInt(3) : 3 + random.nextInt(4); // 0-2 或 3-6
            // 近 6 个月查询次数必然 >= 3 个月
            int query6m = query3m + random.nextInt(5);

            // 总负债/总收入比（0.05 ~ 0.85）
            double debtToIncome = round2(0.05 + random.nextDouble() * 0.80);

            // 历史逾期次数（M1/M2/M3，越严重越少见）
            int overdueM1 = weightedCount(random, new int[]{0, 0, 0, 1, 1, 2, 3});
            int overdueM2 = weightedCount(random, new int[]{0, 0, 0, 0, 1, 2});
            int overdueM3 = weightedCount(random, new int[]{0, 0, 0, 0, 0, 1, 2});

            data.put("credit_query_cnt_3m", query3m);
            data.put("credit_query_cnt_6m", query6m);
            data.put("debt_to_income", debtToIncome);
            data.put("hist_overdue_cnt_m1", overdueM1);
            data.put("hist_overdue_cnt_m2", overdueM2);
            data.put("hist_overdue_cnt_m3", overdueM3);
            data.put("data_version", DATA_VERSION);

            log.info("✅ [模拟征信] 用户 {} 拉取成功：查询(3m/6m)={}/{}, 负债比={}, 逾期(M1/M2/M3)={}/{}/{}",
                    userId, query3m, query6m, debtToIncome, overdueM1, overdueM2, overdueM3);
        } catch (Exception e) {
            // SPI 约定：Provider 不向调用方抛异常（失败返回已收集到的字段/空结构）
            log.error("❌ [模拟征信] 用户 {} 数据生成失败: {}", userId, e.getMessage(), e);
        }
        return data;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean healthCheck() {
        return true;
    }

    // ========== 内部工具 ==========

    /**
     * 以 userId + sourceType 派生的确定性随机源（同一用户每次结果一致）
     */
    static Random deterministicRandom(String userId, String sourceType) {
        long seed = 1125899906842597L; // 大素数，降低低质量 hashCode 的碰撞影响
        if (userId != null) {
            for (int i = 0; i < userId.length(); i++) {
                seed = 31 * seed + userId.charAt(i);
            }
        }
        if (sourceType != null) {
            for (int i = 0; i < sourceType.length(); i++) {
                seed = 31 * seed + sourceType.charAt(i);
            }
        }
        return new Random(seed);
    }

    private static int weightedCount(Random random, int[] weights) {
        return weights[random.nextInt(weights.length)];
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
