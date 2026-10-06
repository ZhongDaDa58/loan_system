package com.loan.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * 多源同步用户范围查询（W4 · 主线 B4）
 *
 * 批量/增量同步的"用户范围"来自授权留痕表：只对**已授权**的用户拉取数据（合规）。
 */
public interface MultiSourceSyncMapper {

    /** 已授权某数据源的用户（批量同步） */
    List<String> selectAuthorizedUsers(@Param("sourceType") String sourceType,
                                       @Param("limit") Integer limit);

    /** 已授权但"数据缺失或过期"的用户（增量同步） */
    List<String> selectStaleAuthorizedUsers(@Param("sourceType") String sourceType,
                                            @Param("threshold") Date threshold,
                                            @Param("limit") Integer limit);
}
