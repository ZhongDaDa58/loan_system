package com.loan.rule;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 第一层「信息校验先行」信号装配器（设计文档 §2 第一层 / §5）
 *
 * <p><b>职责</b>：只做「计算与转换」，把原始数据变成规则引擎能比较的扁平标量；
 * <b>一个判断都不做</b> —— 通过/拒绝的判读全部交给 {@code risk_rule}（layer='L1'）。
 *
 * <p><b>设计依据</b>：
 * <ul>
 *   <li>§2：第一层技术实现 = <b>规则引擎</b>；规则引擎只能 {@code field op value}，不能查库/调服务/算术</li>
 *   <li>§3.5 红线：即时信号<b>不走</b> 1.5 层特征工程（那层做聚合/长期特征，供模型入模）</li>
 *   <li>输出契约：扁平标量 + 可用性标志；<b>算不出时给哨兵值 -1，且必须仍放入上下文</b>
 *       —— 否则会重演「字段缺失 = 不命中 = 静默放行」（测试问题 P-031）</li>
 * </ul>
 *
 * <p><b>当前阶段（阶段 A）</b>：不依赖任何 Mapper ——
 * S1/S2（活体）、S5（指纹跨账号）、S6/S7/S8（地址定位）均返回哨兵值；
 * 仅 S3/S4（设备）与 S9（行为）取自已入库的多源数据块。
 * 阶段 C 补齐数据来源后，本类需改造为 Spring Bean 以注入 Mapper。
 *
 * <p>信号契约明细见 {@code finance_dev/信息校验先行开发文档.md} §4。
 */
public final class L1SignalAssembler {

    /** 哨兵值：信号无法计算时使用（仍放入上下文，供规则显式判断"算不出来"） */
    public static final int UNAVAILABLE = -1;

    private L1SignalAssembler() {
    }

    /**
     * 装配第一层规则上下文所需的全部派生信号（S1~S10 全量）。
     *
     * @param blocks 多源数据块（{@code MultiSourceProfileVO.getDataBlocks()}），可为 null
     * @return 扁平标量 Map（含哨兵值与 available 标志，规则可直接比较）
     */
    public static Map<String, Object> assemble(Map<String, Object> blocks) {
        return assemble(blocks, null, null, null);
    }

    public static Map<String, Object> assemble(Map<String, Object> blocks,
                                               com.loan.entity.UserFaceLog faceLog) {
        return assemble(blocks, faceLog, null, null);
    }

    public static Map<String, Object> assemble(Map<String, Object> blocks,
                                               com.loan.entity.UserFaceLog faceLog,
                                               com.loan.entity.UserCreditProfile profile) {
        return assemble(blocks, faceLog, profile, null);
    }

    /**
     * 装配第一层规则上下文所需的全部派生信号（S1~S10 全量）。
     *
     * @param blocks  多源数据块（{@code MultiSourceProfileVO.getDataBlocks()}），可为 null
     * @param faceLog 用户最近一次人脸/活体记录（{@code user_face_log}），可为 null（表示未做活体）
     * @param profile 用户信用档案（提供 {@code home_address} / {@code company_address}），可为 null
     * @param deviceBlacklistHitCnt 同一设备指纹关联的**其它用户数**（阶段 C5，需调用方跨账号查询）；
     *                              null 表示未查询或无指纹 → 产出哨兵值
     * @return 扁平标量 Map（含哨兵值与 available 标志，规则可直接比较）
     */
    public static Map<String, Object> assemble(Map<String, Object> blocks,
                                               com.loan.entity.UserFaceLog faceLog,
                                               com.loan.entity.UserCreditProfile profile,
                                               Integer deviceBlacklistHitCnt) {
        Map<String, Object> ctx = new LinkedHashMap<>();

        // ===== S1/S2 活体（阶段 C1 已接入 user_face_log）=====
        if (faceLog == null) {
            ctx.put("liveness_passed", UNAVAILABLE);   // 未做活体 → 哨兵值 + available=0
            ctx.put("liveness_available", 0);
        } else {
            ctx.put("liveness_available", 1);
            boolean passed = faceLog.getSimilarityScore() != null
                    && faceLog.getSimilarityScore().doubleValue() >= 80d
                    && faceLog.getIsLive() != null && faceLog.getIsLive() == 1;
            ctx.put("liveness_passed", passed ? 1 : 0);
        }

        // ===== S3/S4 设备可信（已就绪：多源 device 块）=====
        Map<String, Object> device = blockOf(blocks, "device");
        boolean deviceAvailable = !device.isEmpty();
        ctx.put("device_available", deviceAvailable ? 1 : 0);
        if (deviceAvailable) {
            boolean trusted = isZero(device.get("device_emulator_flag"))
                    && isZero(device.get("device_blacklist_hit_now"));
            ctx.put("device_trusted", trusted ? 1 : 0);
        } else {
            ctx.put("device_trusted", UNAVAILABLE);
        }

        // ===== S5 设备指纹跨账号聚集（阶段 C5：由调用方跨账号查询后传入）=====
        // null 表示未查询 / 无指纹 → 哨兵值（规则侧可显式判断"算不出来"，避免静默放行）
        ctx.put("device_blacklist_hit_cnt",
                deviceBlacklistHitCnt == null ? UNAVAILABLE : deviceBlacklistHitCnt);

        // ===== S6/S7/S8 地址与定位交叉验证（阶段 C4：真实计算）=====
        // 数据来源：① 档案的 home_address / company_address（阶段 C2，可选填）
        //          ② 多源 location 块的 latitude / longitude（阶段 C3，App 上报）
        String address = null;
        if (profile != null) {
            String home = profile.getHomeAddress();
            address = (home != null && !home.isBlank()) ? home : profile.getCompanyAddress();
        }
        Map<String, Object> location = blockOf(blocks, "location");
        Double lat = location.get("latitude") instanceof Number n1 ? n1.doubleValue() : null;
        Double lon = location.get("longitude") instanceof Number n2 ? n2.doubleValue() : null;
        double[] city = com.loan.util.GeoCodeUtil.locate(address);

        if (lat == null || lon == null || city == null) {
            // 无定位 / 无地址 / 地址解析不出城市 → **无法判定**（哨兵值 + available=0）
            // 由 L1-11「定位数据缺失（无法判定）」决定转人工，而不是静默放行
            ctx.put("gps_addr_distance_km", UNAVAILABLE);
            ctx.put("geo_available", 0);
            ctx.put("ip_geo_consistent", UNAVAILABLE);
        } else {
            double km = com.loan.util.GeoCodeUtil.distanceKm(city[0], city[1], lat, lon);
            double rounded = Math.round(km * 100d) / 100d;
            ctx.put("gps_addr_distance_km", rounded);
            ctx.put("geo_available", 1);
            // 同城粗判（阈值仅作信号产出，真正判读交给 L1-10 / L1-12 规则）
            ctx.put("ip_geo_consistent", km < 50d ? 1 : 0);
            // L1-05 依赖的长期偏离字段：暂以本次距离兜底（完整版可按历史聚合，见开发文档 §11 D5）
            ctx.put("gps_deviation_km_longterm", rounded);
        }

        // ===== S9 行为异常分（已就绪：多源 behavior 块）=====
        Map<String, Object> behavior = blockOf(blocks, "behavior");
        Object anomaly = behavior.get("behavior_anomaly_score");
        ctx.put("behavior_available", anomaly == null ? 0 : 1);
        ctx.put("behavior_anomaly_score", anomaly == null ? UNAVAILABLE : toDecimal(anomaly));

        // ===== S10 综合可信度（依赖以上各信号，纯加权，不含通过/拒绝判断）=====
        ctx.put("trust_level", computeTrustLevel(ctx));

        return ctx;
    }

    /** 取多源数据块（缺失时返回空 Map，避免 NPE） */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> blockOf(Map<String, Object> blocks, String key) {
        if (blocks == null) {
            return Collections.emptyMap();
        }
        Object v = blocks.get(key);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Collections.emptyMap();
    }

    /** 多源字段值是否等于 0（兼容 Number 与字符串） */
    private static boolean isZero(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Number n) {
            return n.doubleValue() == 0d;
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim()) == 0d;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 保留两位小数；无法解析时返回哨兵值 */
    private static Object toDecimal(Object v) {
        if (v instanceof Number n) {
            return Math.round(n.doubleValue() * 100d) / 100d;
        }
        try {
            return Math.round(Double.parseDouble(String.valueOf(v).trim()) * 100d) / 100d;
        } catch (NumberFormatException e) {
            return UNAVAILABLE;
        }
    }

    /**
     * 综合可信度合成（契约见开发文档 §4.3）：
     * 3=HIGH / 2=MEDIUM / 1=LOW / 0=UNKNOWN
     *
     * <p><b>注意</b>：这里只做"算术"，不表达业务策略 ——
     * 具体阈值（0.8 / 50km / &lt;2）的可调部分应由 L1 规则承担；
     * 本方法仅把多维度压缩成一个标量供规则比较。
     */
    private static int computeTrustLevel(Map<String, Object> c) {
        // 一条数据都没有 → UNKNOWN，不做任何推断（交由 L1-11/L1-14 显式处理）
        if (asInt(c.get("device_available")) == 0
                && asInt(c.get("behavior_available")) == 0
                && asInt(c.get("liveness_available")) == 0
                && asInt(c.get("geo_available")) == 0) {
            return 0;
        }

        // 设备不可信（含"无设备数据"）→ LOW
        if (asInt(c.get("device_trusted")) != 1) {
            return 1;
        }

        int level = 3;   // 起始 HIGH，逐项取上限
        if (asInt(c.get("behavior_available")) == 1
                && asDouble(c.get("behavior_anomaly_score")) >= 0.8d) {
            level = Math.min(level, 2);
        }
        if (asInt(c.get("liveness_passed")) != 1) {          // 未通过或未做
            level = Math.min(level, 2);
        }
        if (asInt(c.get("geo_available")) == 0) {            // 无法判定
            level = Math.min(level, 2);
        } else if (asDouble(c.get("gps_addr_distance_km")) >= 50d) {   // 严重偏离
            level = Math.min(level, 2);
        }
        return level;
    }

    private static int asInt(Object v) {
        return v instanceof Number n ? n.intValue() : UNAVAILABLE;
    }

    private static double asDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : Double.NaN;
    }
}
