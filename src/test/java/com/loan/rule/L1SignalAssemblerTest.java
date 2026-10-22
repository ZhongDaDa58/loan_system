package com.loan.rule;

import com.loan.entity.UserCreditProfile;
import com.loan.entity.UserFaceLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link L1SignalAssembler} 单元测试（阶段三 3.3）
 *
 * <p><b>为什么优先测它</b>：纯静态方法、零依赖（不启 Spring、不连库）、
 * 10 个信号每个都有"正常 / 异常 / 算不出"三种情况 → 天然适合穷举。
 *
 * <p><b>核心契约（本测试重点守护）</b>：
 * <ul>
 *   <li>算不出的信号 → 必须给哨兵值 {@code -1}，<b>且仍放入上下文</b>
 *       （否则规则"不命中" → 静默放行，这是 P-031 的教训）</li>
 *   <li>每个信号都有配套的 {@code xxx_available} 标志</li>
 *   <li>{@code trust_level} 的合成规则：3=HIGH / 2=MEDIUM / 1=LOW / 0=UNKNOWN</li>
 * </ul>
 */
class L1SignalAssemblerTest {

    private static final int UNAVAILABLE = L1SignalAssembler.UNAVAILABLE;

    // ==================== 测试数据构造 ====================

    /** 正常设备块（非模拟器、未命中黑名单） */
    private static Map<String, Object> normalDevice() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("device_emulator_flag", 0);
        d.put("device_blacklist_hit_now", 0);
        d.put("device_fingerprint_hash", "fp-abc");
        return d;
    }

    /** 上海定位（与"浦东世纪大道"同城） */
    private static Map<String, Object> shanghaiLocation() {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("latitude", 31.2304);
        l.put("longitude", 121.4737);
        return l;
    }

    /** 北京定位 */
    private static Map<String, Object> beijingLocation() {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("latitude", 39.9042);
        l.put("longitude", 116.4074);
        return l;
    }

    private static Map<String, Object> blocks(Map<String, Object> device,
                                              Map<String, Object> location,
                                              Double behaviorAnomaly) {
        Map<String, Object> b = new HashMap<>();
        if (device != null) b.put("device", device);
        if (location != null) b.put("location", location);
        if (behaviorAnomaly != null) {
            Map<String, Object> beh = new LinkedHashMap<>();
            beh.put("behavior_anomaly_score", behaviorAnomaly);
            b.put("behavior", beh);
        }
        return b;
    }

    private static UserFaceLog faceLog(double similarity, int isLive) {
        UserFaceLog f = new UserFaceLog();
        f.setSimilarityScore(BigDecimal.valueOf(similarity));
        f.setIsLive(isLive);
        return f;
    }

    private static UserCreditProfile profileWithAddress(String home) {
        UserCreditProfile p = new UserCreditProfile();
        p.setHomeAddress(home);
        return p;
    }

    // ==================== S1 / S2：活体 ====================

    @Nested
    @DisplayName("S1/S2 活体信号")
    class Liveness {

        @Test
        @DisplayName("无活体记录 → liveness_passed 为哨兵值，liveness_available=0")
        void noRecordGivesSentinel() {
            Map<String, Object> ctx = L1SignalAssembler.assemble(blocks(normalDevice(), shanghaiLocation(), 0.1), null, null, null);

            assertEquals(UNAVAILABLE, ctx.get("liveness_passed"), "未做活体必须给哨兵值");
            assertEquals(0, ctx.get("liveness_available"), "必须标记为不可用");
            assertTrue(ctx.containsKey("liveness_passed"), "哨兵值也必须放进上下文，否则规则不命中=静默放行");
        }

        @Test
        @DisplayName("相似度 85 且 isLive=1 → 通过")
        void passedWhenSimilarityHighAndLive() {
            Map<String, Object> ctx = L1SignalAssembler.assemble(
                    blocks(normalDevice(), shanghaiLocation(), 0.1), faceLog(85, 1), null, null);

            assertEquals(1, ctx.get("liveness_passed"));
            assertEquals(1, ctx.get("liveness_available"));
        }

        @Test
        @DisplayName("相似度恰好 80 → 通过（边界值）")
        void passedAtExactly80() {
            Map<String, Object> ctx = L1SignalAssembler.assemble(
                    blocks(normalDevice(), shanghaiLocation(), 0.1), faceLog(80, 1), null, null);
            assertEquals(1, ctx.get("liveness_passed"), "阈值是 >=80，80 应通过");
        }

        @Test
        @DisplayName("相似度 79.9 → 不通过（边界值）")
        void failedBelow80() {
            Map<String, Object> ctx = L1SignalAssembler.assemble(
                    blocks(normalDevice(), shanghaiLocation(), 0.1), faceLog(79.9, 1), null, null);
            assertEquals(0, ctx.get("liveness_passed"));
        }

        @Test
        @DisplayName("相似度够但 isLive=0 → 不通过（活体检测未通过）")
        void failedWhenNotLive() {
            Map<String, Object> ctx = L1SignalAssembler.assemble(
                    blocks(normalDevice(), shanghaiLocation(), 0.1), faceLog(95, 0), null, null);
            assertEquals(0, ctx.get("liveness_passed"));
            assertEquals(1, ctx.get("liveness_available"));
        }
    }

    // ==================== S3 / S4：设备 ====================

    @Nested
    @DisplayName("S3/S4 设备可信")
    class Device {

        @Test
        @DisplayName("无设备数据 → device_trusted 为哨兵值（不是 0）")
        void noDeviceGivesSentinelNotZero() {
            Map<String, Object> ctx = L1SignalAssembler.assemble(
                    blocks(null, shanghaiLocation(), 0.1), faceLog(90, 1), null, null);

            assertEquals(0, ctx.get("device_available"));
            assertEquals(UNAVAILABLE, ctx.get("device_trusted"),
                    "无数据 ≠ 不可信，必须是哨兵值，否则规则无法区分'数据缺失'和'设备有问题'");
        }

        @Test
        @DisplayName("非模拟器 + 未命中黑名单 → trusted=1")
        void trustedWhenClean() {
            Map<String, Object> ctx = L1SignalAssembler.assemble(
                    blocks(normalDevice(), shanghaiLocation(), 0.1), faceLog(90, 1), null, null);
            assertEquals(1, ctx.get("device_available"));
            assertEquals(1, ctx.get("device_trusted"));
        }

        @Test
        @DisplayName("模拟器 → trusted=0")
        void untrustedWhenEmulator() {
            Map<String, Object> d = normalDevice();
            d.put("device_emulator_flag", 1);
            Map<String, Object> ctx = L1SignalAssembler.assemble(
                    blocks(d, shanghaiLocation(), 0.1), faceLog(90, 1), null, null);
            assertEquals(0, ctx.get("device_trusted"));
        }

        @Test
        @DisplayName("命中黑名单 → trusted=0")
        void untrustedWhenBlacklisted() {
            Map<String, Object> d = normalDevice();
            d.put("device_blacklist_hit_now", 1);
            Map<String, Object> ctx = L1SignalAssembler.assemble(
                    blocks(d, shanghaiLocation(), 0.1), faceLog(90, 1), null, null);
            assertEquals(0, ctx.get("device_trusted"));
        }
    }

    // ==================== S5：跨账号 ====================

    @Nested
    @DisplayName("S5 设备指纹跨账号")
    class CrossAccount {

        @Test
        @DisplayName("未查询 → 哨兵值")
        void nullCountGivesSentinel() {
            Map<String, Object> ctx = L1SignalAssembler.assemble(
                    blocks(normalDevice(), shanghaiLocation(), 0.1), faceLog(90, 1), null, null);
            assertEquals(UNAVAILABLE, ctx.get("device_blacklist_hit_cnt"));
        }

        @Test
        @DisplayName("有关联用户数 → 原值透传")
        void countPassesThrough() {
            Map<String, Object> ctx = L1SignalAssembler.assemble(
                    blocks(normalDevice(), shanghaiLocation(), 0.1), faceLog(90, 1), null, 3);
            assertEquals(3, ctx.get("device_blacklist_hit_cnt"));
        }
    }

    // ==================== S6 / S7 / S8：地址与定位 ====================

    @Nested
    @DisplayName("S6/S7/S8 地址与定位交叉验证")
    class Geo {

        @Test
        @DisplayName("既无地址又无定位 → geo_available=0（交给 L1-11 转人工，而非静默放行）")
        void noAddressNoLocation() {
            Map<String, Object> ctx = L1SignalAssembler.assemble(
                    blocks(normalDevice(), null, 0.1), faceLog(90, 1), null, null);

            assertEquals(0, ctx.get("geo_available"));
            assertEquals(UNAVAILABLE, ctx.get("gps_addr_distance_km"));
            assertTrue(ctx.containsKey("geo_available"), "必须显式标记不可用");
        }

        @Test
        @DisplayName("有地址但无定位 → geo_available=0")
        void addressButNoLocation() {
            Map<String, Object> ctx = L1SignalAssembler.assemble(
                    blocks(normalDevice(), null, 0.1), faceLog(90, 1),
                    profileWithAddress("上海市浦东新区世纪大道100号"), null);
            assertEquals(0, ctx.get("geo_available"));
        }

        @Test
        @DisplayName("上海地址 + 上海定位 → 距离很小，ip_geo_consistent=1")
        void sameCity() {
            Map<String, Object> ctx = L1SignalAssembler.assemble(
                    blocks(normalDevice(), shanghaiLocation(), 0.1), faceLog(90, 1),
                    profileWithAddress("上海市浦东新区世纪大道100号"), null);

            assertEquals(1, ctx.get("geo_available"));
            double km = ((Number) ctx.get("gps_addr_distance_km")).doubleValue();
            assertTrue(km < 50, "同城距离应远小于 50km，实际 " + km);
            assertEquals(1, ctx.get("ip_geo_consistent"));
        }

        @Test
        @DisplayName("上海地址 + 北京定位 → 超过 1000km，ip_geo_consistent=0")
        void crossCity() {
            Map<String, Object> ctx = L1SignalAssembler.assemble(
                    blocks(normalDevice(), beijingLocation(), 0.1), faceLog(90, 1),
                    profileWithAddress("上海市浦东新区世纪大道100号"), null);

            assertEquals(1, ctx.get("geo_available"));
            double km = ((Number) ctx.get("gps_addr_distance_km")).doubleValue();
            assertTrue(km > 1000, "上海到北京应超过 1000km，实际 " + km);
            assertEquals(0, ctx.get("ip_geo_consistent"));
        }

        @Test
        @DisplayName("无家庭地址时回退公司地址")
        void fallbackToCompanyAddress() {
            UserCreditProfile p = new UserCreditProfile();
            p.setCompanyAddress("上海市浦东新区世纪大道100号");
            Map<String, Object> ctx = L1SignalAssembler.assemble(
                    blocks(normalDevice(), shanghaiLocation(), 0.1), faceLog(90, 1), p, null);
            assertEquals(1, ctx.get("geo_available"), "家庭地址为空应回退公司地址");
        }

        @Test
        @DisplayName("距离保留两位小数")
        void distanceRoundedToTwoDecimals() {
            Map<String, Object> ctx = L1SignalAssembler.assemble(
                    blocks(normalDevice(), beijingLocation(), 0.1), faceLog(90, 1),
                    profileWithAddress("上海市浦东新区世纪大道100号"), null);
            double km = ((Number) ctx.get("gps_addr_distance_km")).doubleValue();
            assertEquals(Math.round(km * 100d) / 100d, km, 0.0001);
        }
    }

    // ==================== S9：行为异常 ====================

    @Nested
    @DisplayName("S9 行为异常分")
    class Behavior {

        @Test
        @DisplayName("无行为数据 → available=0 且值为哨兵值")
        void noBehavior() {
            Map<String, Object> ctx = L1SignalAssembler.assemble(
                    blocks(normalDevice(), shanghaiLocation(), null), faceLog(90, 1), null, null);
            assertEquals(0, ctx.get("behavior_available"));
            assertEquals(UNAVAILABLE, ctx.get("behavior_anomaly_score"));
        }

        @Test
        @DisplayName("有行为数据 → 保留两位小数")
        void behaviorRounded() {
            Map<String, Object> ctx = L1SignalAssembler.assemble(
                    blocks(normalDevice(), shanghaiLocation(), 0.87654), faceLog(90, 1), null, null);
            assertEquals(1, ctx.get("behavior_available"));
            assertEquals(0.88, ((Number) ctx.get("behavior_anomaly_score")).doubleValue(), 0.001);
        }
    }

    // ==================== S10：可信度合成 ====================

    @Nested
    @DisplayName("S10 综合可信度 trust_level")
    class TrustLevel {

        @Test
        @DisplayName("一条数据都没有 → trust_level=0（UNKNOWN，不做推断）")
        void allMissingGivesUnknown() {
            Map<String, Object> ctx = L1SignalAssembler.assemble(
                    new HashMap<>(), null, null, null);
            assertEquals(0, ctx.get("trust_level"));
        }

        @Test
        @DisplayName("★ 无设备数据 → trust_level=1（LOW）—— 这是实测确认过的行为")
        void noDeviceGivesLow() {
            Map<String, Object> ctx = L1SignalAssembler.assemble(
                    blocks(null, shanghaiLocation(), 0.1), faceLog(90, 1),
                    profileWithAddress("上海市浦东新区世纪大道100号"), null);

            assertEquals(1, ctx.get("trust_level"),
                    "设备不可信（含无数据）直接降到 LOW，会命中 L1-14");
        }

        @Test
        @DisplayName("全部正常 → trust_level=3（HIGH）")
        void allGoodGivesHigh() {
            Map<String, Object> ctx = L1SignalAssembler.assemble(
                    blocks(normalDevice(), shanghaiLocation(), 0.1), faceLog(90, 1),
                    profileWithAddress("上海市浦东新区世纪大道100号"), null);
            assertEquals(3, ctx.get("trust_level"));
        }

        @Test
        @DisplayName("活体未通过 → 降为 2（MEDIUM）")
        void livenessFailDowngrades() {
            Map<String, Object> ctx = L1SignalAssembler.assemble(
                    blocks(normalDevice(), shanghaiLocation(), 0.1), faceLog(60, 1),
                    profileWithAddress("上海市浦东新区世纪大道100号"), null);
            assertEquals(2, ctx.get("trust_level"));
        }

        @Test
        @DisplayName("行为异常 >= 0.8 → 降为 2")
        void highAnomalyDowngrades() {
            Map<String, Object> ctx = L1SignalAssembler.assemble(
                    blocks(normalDevice(), shanghaiLocation(), 0.85), faceLog(90, 1),
                    profileWithAddress("上海市浦东新区世纪大道100号"), null);
            assertEquals(2, ctx.get("trust_level"));
        }

        @Test
        @DisplayName("地理严重偏离（>50km）→ 降为 2")
        void geoFarDowngrades() {
            Map<String, Object> ctx = L1SignalAssembler.assemble(
                    blocks(normalDevice(), beijingLocation(), 0.1), faceLog(90, 1),
                    profileWithAddress("上海市浦东新区世纪大道100号"), null);
            assertEquals(2, ctx.get("trust_level"));
        }

        @Test
        @DisplayName("设备正常 + 地理不可判定 → 降为 2")
        void geoUnavailableDowngrades() {
            Map<String, Object> ctx = L1SignalAssembler.assemble(
                    blocks(normalDevice(), null, 0.1), faceLog(90, 1), null, null);
            assertEquals(2, ctx.get("trust_level"));
        }
    }

    // ==================== 输出契约 ====================

    @Nested
    @DisplayName("输出契约：字典必须始终完整")
    class Contract {

        @Test
        @DisplayName("blocks 为 null 也不抛异常，且返回完整字典")
        void nullBlocksIsSafe() {
            Map<String, Object> ctx = L1SignalAssembler.assemble(null, null, null, null);
            assertNotNull(ctx);
            // 10 个信号 + 4 个 available 标志都必须在
            for (String k : new String[]{
                    "liveness_passed", "liveness_available",
                    "device_trusted", "device_available",
                    "device_blacklist_hit_cnt",
                    "gps_addr_distance_km", "geo_available", "ip_geo_consistent",
                    "behavior_anomaly_score", "behavior_available",
                    "trust_level"}) {
                assertTrue(ctx.containsKey(k), "上下文必须包含 " + k + "（缺失会导致规则静默放行）");
            }
        }

        @Test
        @DisplayName("device 块为空 Map 时等同无数据")
        void emptyDeviceBlockEqualsMissing() {
            Map<String, Object> b = new HashMap<>();
            b.put("device", new HashMap<String, Object>());
            Map<String, Object> ctx = L1SignalAssembler.assemble(b, null, null, null);
            assertEquals(0, ctx.get("device_available"));
            assertEquals(UNAVAILABLE, ctx.get("device_trusted"));
        }
    }
}
