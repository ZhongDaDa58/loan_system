package com.loan.mapper;

import com.loan.entity.DataSourceRegistryConfig;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 数据源 Provider 注册配置 Mapper
 * 对应表：data_source_registry
 */
public interface DataSourceRegistryConfigMapper {

    // 查询全部数据源注册配置
    List<DataSourceRegistryConfig> selectAll();

    // 按数据源类型查询
    DataSourceRegistryConfig selectBySourceType(@Param("sourceType") String sourceType);

    // 新增注册配置
    int insert(DataSourceRegistryConfig record);

    // 更新启用开关
    int updateEnabled(@Param("sourceType") String sourceType,
                      @Param("enabled") Integer enabled);

    // 更新熔断状态与健康检查时间
    int updateCircuitStatus(@Param("sourceType") String sourceType,
                            @Param("circuitStatus") String circuitStatus);
}
