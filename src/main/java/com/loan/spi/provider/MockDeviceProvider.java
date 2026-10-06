package com.loan.spi.provider;

import com.loan.entity.enums.SourceTypeEnum;
import com.loan.spi.DataSourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * 设备指纹 / 反欺诈数据源 —— 模拟 Provider（W4 · 主线 B1 实现）
 *
 * 按《多源数据.md》设备指纹源口径返回字段（键名即特征名）：
 *
 * 规则层（即时拦截，见《多源数据.md》4.1）：
 *   device_emulator_flag      模拟器/root 标记（0/1）
 *   device_blacklist_hit_now  当前是否命中黑名单（0/1）
 *   device_multi_account_now  当前多账号聚集迹象（0/1）
 *
 * 入模候选（加工后稳定特征，见《多源数据.md》3.5）：
 *   device_blacklist_hit_cnt   历史命中黑名单次数
 *   device_related_app_cnt_30d 近 30 天同设备关联申请数
 *   device_first_seen_months   设备首次出现距今月数
 *
 * 网络环境：
 *   device_fingerprint_hash    设备指纹（脱敏 hash）
 *   ip_proxy_risk              IP 代理/VPN 风险分（0-1）
 *   ip_geo_mismatch            IP 属地与填写地址不符（0/1）
 *
 * 生成方式：以 userId 为种子的**确定性伪随机**——同一用户结果稳定，便于联调与复现。
 * 风险特征按真实反欺诈的"低发生率"注入（模拟器/黑名单命中仅少数），避免虚高。
 */
@Component
public class MockDeviceProvider implements DataSourceProvider {

    private static final Logger log = LoggerFactory.getLogger(MockDeviceProvider.class);

    /** 风险事件发生率（模拟真实反欺诈的低频特征） */
    private static final int EMULATOR_RATE_PCT = 3;
    private static final int BLACKLIST_NOW_RATE_PCT = 2;
    private static final int MULTI_ACCOUNT_RATE_PCT = 4;
    private static final int IP_MISMATCH_RATE_PCT = 5;

    @Override
    public String sourceType() {
        return SourceTypeEnum.DEVICE.getCode();
    }

    @Override
    public Map<String, Object> fetch(String userId) {
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            Random random = MockCreditProvider.deterministicRandom(userId, sourceType());

            data.put("device_fingerprint_hash", fingerprintHash(userId));

            // ---- 规则层：即时信号（低频命中） ----
            boolean emulator = random.nextInt(100) < EMULATOR_RATE_PCT;
            boolean blacklistNow = random.nextInt(100) < BLACKLIST_NOW_RATE_PCT;
            boolean multiAccount = random.nextInt(100) < MULTI_ACCOUNT_RATE_PCT;
            data.put("device_emulator_flag", emulator ? 1 : 0);
            data.put("device_blacklist_hit_now", blacklistNow ? 1 : 0);
            data.put("device_multi_account_now", multiAccount ? 1 : 0);

            // ---- 入模候选：聚合/稳定特征 ----
            int blacklistHitCnt = weightedCount(random, new int[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 2, 3});
            data.put("device_blacklist_hit_cnt", blacklistHitCnt);

            int relatedAppCnt = weightedCount(random, new int[]{0, 0, 1, 1, 1, 2, 2, 3, 4, 5, 8});
            data.put("device_related_app_cnt_30d", relatedAppCnt);

            int firstSeenMonths = 6 + random.nextInt(55); // 6-60 个月
            data.put("device_first_seen_months", firstSeenMonths);

            // ---- 网络环境 ----
            double proxyRisk = Math.round(Math.min(1.0, random.nextDouble() * random.nextDouble() * 1.6) * 100.0) / 100.0;
            boolean ipMismatch = random.nextInt(100) < IP_MISMATCH_RATE_PCT;
            data.put("ip_proxy_risk", proxyRisk);
            data.put("ip_geo_mismatch", ipMismatch ? 1 : 0);

            data.put("data_version", "v1");

            log.info("✅ [模拟设备指纹] 用户 {} 拉取成功：指纹={}…, 模拟器={}, 黑名单(当前/历史)={}/{}, "
                            + "关联申请={}, 首现={}月, IP风险={}, 属地不符={}",
                    userId, String.valueOf(data.get("device_fingerprint_hash")).substring(0, Math.min(8,
                            String.valueOf(data.get("device_fingerprint_hash")).length())),
                    emulator ? 1 : 0, blacklistNow ? 1 : 0, blacklistHitCnt, relatedAppCnt,
                    firstSeenMonths, proxyRisk, ipMismatch ? 1 : 0);
        } catch (Exception e) {
            // SPI 约定：不向调用方抛异常
            log.error("❌ [模拟设备指纹] 用户 {} 数据生成失败: {}", userId, e.getMessage(), e);
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

    // ==================== 内部工具 ====================

    /** 设备指纹（脱敏 hash）：同一用户稳定，且不含任何原始设备标识 */
    private static String fingerprintHash(String userId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(("DEVICE|" + (userId == null ? "anonymous" : userId))
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static int weightedCount(Random random, int[] weights) {
        return weights[random.nextInt(weights.length)];
    }
}
