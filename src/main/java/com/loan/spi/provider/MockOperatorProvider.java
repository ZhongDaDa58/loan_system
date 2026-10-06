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
 * 运营商数据源 —— 模拟 Provider（W3 实现）
 *
 * 按《多源数据.md》运营商源口径返回标准化字段（键名即特征名）：
 *   operator_realname_months（实名认证时长，月）
 *   operator_tenure_months（在网时长，月，<= 实名时长）
 *   operator_pay_stability（缴费稳定度：连续缴费月数，0-36）
 *
 * 字段自洽约束：在网时长 <= 实名时长；实名时长 <= 实名制时间窗上限（13 年）。
 * 生成方式：以 userId 为种子的确定性伪随机（同一用户结果稳定）。
 */
@Component
public class MockOperatorProvider implements DataSourceProvider {

    private static final Logger log = LoggerFactory.getLogger(MockOperatorProvider.class);

    /** 实名制时间窗上限（月）：手机号实名制自 2013 年起，取 13 年 ≈ 156 个月 */
    private static final int REALNAME_CAP_MONTHS = 156;

    @Override
    public String sourceType() {
        return SourceTypeEnum.OPERATOR.getCode();
    }

    @Override
    public Map<String, Object> fetch(String userId) {
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            Random random = MockCreditProvider.deterministicRandom(userId, sourceType());

            // 实名时长：多数用户 2 年以上，少量新入网
            int realnameMonths = 24 + random.nextInt(REALNAME_CAP_MONTHS - 24 + 1);
            if (random.nextInt(100) < 15) { // 15% 为新入网用户（<24 个月）
                realnameMonths = 3 + random.nextInt(21);
            }

            // 在网时长 <= 实名时长
            int tenureMonths = (int) Math.round(realnameMonths * (0.5 + random.nextDouble() * 0.5));

            // 缴费稳定度：连续缴费月数（越接近在网时长越稳定，叠加噪声）
            int payStability = (int) Math.min(36, Math.round(tenureMonths * (0.4 + random.nextDouble() * 0.6)));

            data.put("operator_realname_months", realnameMonths);
            data.put("operator_tenure_months", tenureMonths);
            data.put("operator_pay_stability", payStability);
            data.put("data_version", "v1");

            log.info("✅ [模拟运营商] 用户 {} 拉取成功：实名 {} 月 / 在网 {} 月 / 缴费稳定度 {}",
                    userId, realnameMonths, tenureMonths, payStability);
        } catch (Exception e) {
            log.error("❌ [模拟运营商] 用户 {} 数据生成失败: {}", userId, e.getMessage(), e);
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
}
