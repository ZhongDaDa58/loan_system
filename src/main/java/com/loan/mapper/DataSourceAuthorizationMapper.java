package com.loan.mapper;

import com.loan.entity.DataSourceAuthorization;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * 多数据源授权留痕 Mapper
 * 对应表：data_source_authorization
 */
public interface DataSourceAuthorizationMapper {

    // 按用户 + 数据源查询单条授权记录
    DataSourceAuthorization selectByUserIdAndSourceType(@Param("userId") String userId,
                                                        @Param("sourceType") String sourceType);

    // 查询用户全部授权记录
    List<DataSourceAuthorization> selectByUserId(@Param("userId") String userId);

    // 新增授权记录
    int insert(DataSourceAuthorization record);

    // 更新授权状态（含凭证与有效期）
    int updateAuthStatus(@Param("userId") String userId,
                         @Param("sourceType") String sourceType,
                         @Param("authStatus") String authStatus,
                         @Param("authToken") String authToken,
                         @Param("authorizedAt") Date authorizedAt,
                         @Param("expiryAt") Date expiryAt);
}
