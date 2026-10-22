package com.loan.util;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 请求级缓存（阶段二 2.2a）
 *
 * <p><b>解决什么问题</b>：压测与 SQL 计数发现，一次贷款申请要执行 <b>30 条 SQL</b>，
 * 其中 <b>19 次是"查同一用户的数据"</b>（多源档案 × 5、用户身份 × 5、授权 × 3、活体 × 2…）。
 * 根因是 {@code evaluateRiskRulesByLayer} 被调用两次（L1 与 L3 各一次），
 * 而它内部每次都会重新查一遍多源档案、活体记录、信用档案。
 *
 * <p><b>为什么不用 Redis</b>：这是<b>同一请求内的重复查询</b>，走 Redis 仍要网络往返 +
 * 序列化，不比直接查库快多少。正确的手段是<b>请求内复用内存结果</b>。
 *
 * <p><b>为什么用 ThreadLocal 而不是 {@code @Scope("request")}</b>：
 * {@code @Scope("request")} 在非 Web 线程（定时任务、{@code @Async}）中会抛异常；
 * ThreadLocal 无此问题，且清理点可与 {@code TraceIdFilter} 的 {@code finally} 对齐。
 *
 * <p><b>⚠️ 必须清理</b>：{@link #clear()} 已在 {@code TraceIdFilter} 的 {@code finally} 中调用。
 * Tomcat 线程池会复用线程，不清理会把上一个请求的数据带给下一个请求 ——
 * 这在风控场景是<b>数据串号</b>，后果比性能问题严重得多。
 *
 * <p><b>使用约束</b>：
 * <ul>
 *   <li>仅用于<b>请求内只读</b>数据（档案、多源、活体、规则、产品等）</li>
 *   <li><b>不要缓存会在同一请求内被修改的数据</b>，否则读到旧值</li>
 * </ul>
 */
public final class RequestCache {

    private static final ThreadLocal<Map<String, Object>> CACHE = ThreadLocal.withInitial(HashMap::new);

    private RequestCache() {
    }

    /**
     * 取缓存值；未命中则调用 {@code loader} 并写入缓存。
     *
     * <p>注意：{@code loader} 返回 {@code null} 时<b>也会缓存 null</b>（用哨兵键区分），
     * 避免"查不到 → 每次重查"的缓存穿透。
     */
    @SuppressWarnings("unchecked")
    public static <T> T get(String key, Supplier<T> loader) {
        Map<String, Object> map = CACHE.get();
        String nullMark = key + "\u0000NULL";
        if (map.containsKey(key)) {
            return (T) map.get(key);
        }
        if (map.containsKey(nullMark)) {
            return null;
        }
        T value = loader.get();
        if (value == null) {
            map.put(nullMark, Boolean.TRUE);
        } else {
            map.put(key, value);
        }
        return value;
    }

    /** 清空当前请求的缓存（由 TraceIdFilter 在 finally 中调用） */
    public static void clear() {
        CACHE.remove();
    }

    /** 当前请求已缓存的条目数（调试用） */
    public static int size() {
        return CACHE.get().size();
    }
}
