package com.loan.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 城市级地理编码工具（第一层「信息校验先行」，阶段 C4）
 *
 * <p><b>设计依据</b>：
 * <ul>
 *   <li>设计文档 §5② 要求「利用 GPS、IP、运营商数据交叉验证用户填写的单位/家庭地址真实性」</li>
 *   <li>决策 D3：采用 <b>本地城市级坐标表</b> —— 无外部依赖、可离线、毫秒级</li>
 * </ul>
 *
 * <p><b>能力边界（务必知悉）</b>：
 * <ul>
 *   <li>只做 <b>城市级</b> 解析：从地址字符串中匹配城市名 → 取该城市中心经纬度</li>
 *   <li>匹配不到 → 返回 {@code null}（调用方据此判定 {@code geo_available = 0}，<b>不判负</b>，
 *       由 L1-11 规则决定"无法判定 → 转人工"）</li>
 *   <li>距离用哈弗辛公式，<b>城市级精度足够</b>做"是否同城 / 严重偏离"的判断</li>
 * </ul>
 *
 * <p>本类只做<b>计算</b>，不含任何判读（阈值一律由 L1 规则承担）。
 */
public final class GeoCodeUtil {

    /** 地球平均半径（公里） */
    private static final double EARTH_RADIUS_KM = 6371.0;

    /** 城市 → [纬度, 经度]（城市中心点）。可按需扩充；键为城市简称，用 contains 匹配。 */
    private static final Map<String, double[]> CITY_COORDS = new LinkedHashMap<>();

    static {
        put("北京", 39.9042, 116.4074);
        put("上海", 31.2304, 121.4737);
        put("广州", 23.1291, 113.2644);
        put("深圳", 22.5431, 114.0579);
        put("天津", 39.3434, 117.3616);
        put("重庆", 29.5630, 106.5516);
        put("成都", 30.5728, 104.0668);
        put("杭州", 30.2741, 120.1551);
        put("武汉", 30.5928, 114.3055);
        put("西安", 34.3416, 108.9398);
        put("南京", 32.0603, 118.7969);
        put("苏州", 31.2989, 120.5853);
        put("长沙", 28.2282, 112.9388);
        put("郑州", 34.7466, 113.6254);
        put("青岛", 36.0671, 120.3826);
        put("沈阳", 41.8057, 123.4315);
        put("大连", 38.9140, 121.6147);
        put("厦门", 24.4798, 118.0894);
        put("福州", 26.0745, 119.2965);
        put("济南", 36.6512, 117.1201);
        put("合肥", 31.8206, 117.2272);
        put("昆明", 24.8801, 102.8329);
        put("哈尔滨", 45.8038, 126.5349);
        put("长春", 43.8171, 125.3235);
        put("石家庄", 38.0428, 114.5149);
        put("太原", 37.8706, 112.5489);
        put("南昌", 28.6820, 115.8579);
        put("贵阳", 26.6470, 106.6302);
        put("南宁", 22.8170, 108.3665);
        put("兰州", 36.0611, 103.8343);
        put("乌鲁木齐", 43.8256, 87.6168);
        put("呼和浩特", 40.8424, 111.7490);
        put("银川", 38.4872, 106.2309);
        put("西宁", 36.6171, 101.7782);
        put("拉萨", 29.6500, 91.1000);
        put("海口", 20.0444, 110.1999);
        put("三亚", 18.2528, 109.5119);
        put("宁波", 29.8683, 121.5440);
        put("无锡", 31.4912, 120.3119);
        put("佛山", 23.0219, 113.1214);
        put("东莞", 23.0207, 113.7518);
        put("温州", 27.9944, 120.6994);
        put("珠海", 22.2707, 113.5767);
        put("中山", 22.5176, 113.3926);
        put("惠州", 23.1115, 114.4168);
        put("泉州", 24.8741, 118.6757);
        put("常州", 31.8107, 119.9741);
        put("南通", 31.9802, 120.8943);
        put("徐州", 34.2058, 117.2841);
        put("烟台", 37.4638, 121.4479);
        put("唐山", 39.6304, 118.1802);
        put("洛阳", 34.6197, 112.4540);
        put("襄阳", 32.0090, 112.1223);
        put("宜昌", 30.6919, 111.2865);
    }

    private GeoCodeUtil() {
    }

    private static void put(String city, double lat, double lon) {
        CITY_COORDS.put(city, new double[]{lat, lon});
    }

    /**
     * 从地址文本解析城市中心坐标。
     *
     * @param address 用户填写的地址（可为 null / 空）
     * @return {@code [纬度, 经度]}；**解析不出返回 null**（调用方应据此判定"无法判定"，不判负）
     */
    public static double[] locate(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String a = address.trim();
        for (Map.Entry<String, double[]> entry : CITY_COORDS.entrySet()) {
            if (a.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** 判断地址能否解析出城市（供 available 标志使用） */
    public static boolean canLocate(String address) {
        return locate(address) != null;
    }

    /** 哈弗辛公式：两点间大圆距离（公里） */
    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double sinLat = Math.sin(dLat / 2);
        double sinLon = Math.sin(dLon / 2);
        double h = sinLat * sinLat
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * sinLon * sinLon;
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    /** 地址（城市级）与定位坐标之间的距离（公里）；任一无法解析 → 返回 -1（哨兵） */
    public static double distanceFromAddress(String address, Double lat, Double lon) {
        if (lat == null || lon == null) {
            return -1d;
        }
        double[] city = locate(address);
        if (city == null) {
            return -1d;
        }
        return distanceKm(city[0], city[1], lat, lon);
    }
}
