package com.loan.spi;

import java.util.Map;

/**
 * 多数据源接入 SPI（服务提供者接口）
 *
 * 系统只依赖本接口编程，不关心背后是"模拟 Provider"还是"真实机构 Provider"。
 * 将来接入真实数据源：新增一个实现类并注册为 Spring Bean 即可，其余代码不动。
 *
 * 实现约定：
 * - fetch 返回的键名必须遵循《多源数据.md》的字段口径；
 * - 不抛异常给调用方（内部捕获并降级），保证融合链路稳定。
 */
public interface DataSourceProvider {

    /**
     * 数据源类型标识：credit / operator / social / device
     * 见 SourceTypeEnum
     */
    String sourceType();

    /**
     * 拉取并返回标准化字段（键名见《多源数据.md》）
     */
    Map<String, Object> fetch(String userId);

    /**
     * 开关：应与 data_source_registry.enabled 联动
     */
    boolean isEnabled();

    /**
     * 健康检查（供熔断判断）
     */
    boolean healthCheck();
}
