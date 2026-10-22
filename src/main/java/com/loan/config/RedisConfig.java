package com.loan.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置（阶段二 2.2b）
 *
 * <p><b>背景</b>：pom 里早就有 {@code spring-boot-starter-data-redis}，
 * {@code /actuator/health} 也显示 {@code redis: UP}，但<b>代码里一行都没用</b>。
 * 本类把它真正接起来。
 *
 * <p><b>为什么要自定义序列化器</b>：
 * 默认的 {@code JdkSerializationRedisSerializer} 会把值序列化成二进制，
 * 在 {@code redis-cli} 里完全不可读，线上排查困难。统一改成 JSON。
 *
 * <p><b>为什么必须 {@code activateDefaultTyping}</b>：
 * {@code @Cacheable} 的返回类型是 {@code List<RiskRule>}，但泛型在运行时被擦除。
 * 不写入类型信息的话，反序列化出来是 {@code List<LinkedHashMap>}，
 * 强转 {@code List<RiskRule>} 时会抛 {@code ClassCastException} ——
 * 而且现象很有迷惑性：<b>"缓存未命中时接口正常，命中后 500"</b>。
 *
 * <p><b>缓存什么（由 2.1 压测 + SQL 计数决定）</b>：
 * <ul>
 *   <li><b>风控规则列表</b>：每笔申请查 1 次全表，规则极少变更 → 缓存 + 变更时主动清除</li>
 *   <li><b>定价档位</b>：每笔申请查 1 次，几乎不变</li>
 *   <li><b>不缓存</b>：用户档案/多源/活体 —— 这些是"同一请求内的重复"，
 *       已由 2.2a 的 {@code RequestCache} 解决；走 Redis 反而更慢</li>
 * </ul>
 */
@Configuration
@EnableCaching
public class RedisConfig implements org.springframework.cache.annotation.CachingConfigurer {

    /**
     * ★ 缓存故障降级：Redis 不可用时<b>记 warn 并继续走数据库</b>，而不是抛异常。
     *
     * <p><b>为什么必须配这个</b>：
     * Spring Cache 默认用 {@code SimpleCacheErrorHandler}，它会<b>把缓存操作的异常原样抛出</b>。
     * 对本项目意味着：
     * <pre>
     * Redis 挂掉 → @Cacheable 抛异常 → list() 抛异常
     *           → 规则评估 catch 住并「按无命中处理」
     *           → 14 条风控规则全部失效（接口不报错、不 500）
     * </pre>
     * 这比"没有缓存"严重得多 —— <b>缓存是加速手段，不该成为可用性的单点</b>。
     *
     * <p>配上本处理器后：Redis 挂掉 → 打一条 warn → 直接查库 → 业务正常。
     *
     * <p><b>注意</b>：缓存的读/写/清除四类操作都要处理，
     * 只处理 get 的话，{@code @CacheEvict} 失败（如规则更新后清缓存失败）仍会抛异常。
     */
    @Override
    public org.springframework.cache.interceptor.CacheErrorHandler errorHandler() {
        return new org.springframework.cache.interceptor.CacheErrorHandler() {

            @Override
            public void handleCacheGetError(RuntimeException e,
                                            org.springframework.cache.Cache cache, Object key) {
                // 读缓存失败 → 降级：本次直接查库
                log.warn("⚠️ 缓存读取失败，降级查数据库: cache={}, key={}, reason={}",
                        cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException e,
                                            org.springframework.cache.Cache cache, Object key, Object value) {
                // 写缓存失败 → 忽略：本次结果仍然返回给调用方，只是没缓存住
                log.warn("⚠️ 缓存写入失败（本次结果不缓存）: cache={}, key={}, reason={}",
                        cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException e,
                                              org.springframework.cache.Cache cache, Object key) {
                // 清除失败 → 危险：规则已改但缓存仍是旧值，只能等 TTL 过期
                log.error("❌ 缓存清除失败（可能读到旧数据，等待 TTL 过期）: cache={}, key={}",
                        cache.getName(), key, e);
            }

            @Override
            public void handleCacheClearError(RuntimeException e,
                                              org.springframework.cache.Cache cache) {
                log.error("❌ 缓存清空失败: cache={}", cache.getName(), e);
            }
        };
    }

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(RedisConfig.class);

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        // 用 Spring 自带的 GenericJackson2JsonRedisSerializer：
        // 它内部已正确处理类型信息（写入 @class），能还原 List<RiskRule> 等泛型集合。
        // ⚠️ 不要用 `new Jackson2JsonRedisSerializer<>(om, Object.class)` 并手动
        //    om.activateDefaultTyping(...) —— 两者会冲突，导致
        //    "Cannot serialize" 异常，且现象隐蔽：接口不报 500，而是业务层 catch 后
        //    「按无命中处理」静默降级。
        GenericJackson2JsonRedisSerializer json = new GenericJackson2JsonRedisSerializer();
        StringRedisSerializer str = new StringRedisSerializer();

        template.setKeySerializer(str);
        template.setHashKeySerializer(str);
        template.setValueSerializer(json);
        template.setHashValueSerializer(json);
        template.afterPropertiesSet();
        return template;
    }

    /**
     * ★ 关键：{@code @Cacheable} / {@code @CacheEvict} 用的是 Spring Cache 的 CacheManager，
     * <b>不是上面那个 RedisTemplate</b>。两者的序列化器各管各的，互不影响。
     *
     * <p>Spring Boot 在 {@code spring.cache.type=redis} 时会自动创建 RedisCacheManager，
     * 但用的是<b>默认 JDK 序列化</b>（{@code JdkSerializationRedisSerializer}）。
     * 本项目实体（如 {@code RiskRule}）没有 {@code implements Serializable}，
     * 于是写缓存时抛 {@code java.io.NotSerializableException}，
     * 外层看到的消息就是 Spring Cache 报的 <b>"Cannot serialize"</b>。
     *
     * <p>所以必须显式定义 RedisCacheManager 并配 JSON 序列化 ——
     * 否则上面 RedisTemplate 的序列化配置对注解缓存<b>完全不生效</b>。
     */
    @Bean
    public org.springframework.data.redis.cache.RedisCacheManager cacheManager(
            RedisConnectionFactory factory) {
        org.springframework.data.redis.cache.RedisCacheConfiguration config =
                org.springframework.data.redis.cache.RedisCacheConfiguration.defaultCacheConfig()
                        // 与 application.properties 的 spring.cache.redis.time-to-live 保持一致
                        .entryTtl(java.time.Duration.ofMinutes(5))
                        .serializeKeysWith(
                                org.springframework.data.redis.serializer.RedisSerializationContext
                                        .SerializationPair.fromSerializer(new StringRedisSerializer()))
                        .serializeValuesWith(
                                org.springframework.data.redis.serializer.RedisSerializationContext
                                        .SerializationPair.fromSerializer(
                                                new GenericJackson2JsonRedisSerializer()));
        return org.springframework.data.redis.cache.RedisCacheManager
                .builder(factory)
                .cacheDefaults(config)
                .build();
    }
}
