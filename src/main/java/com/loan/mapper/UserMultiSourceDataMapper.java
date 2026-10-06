package com.loan.mapper;

import com.loan.entity.UserMultiSourceData;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 多源标准化数据 Mapper
 * 对应表：user_multi_source_data
 */
public interface UserMultiSourceDataMapper {

    // 按用户 + 数据源查询最新一条
    UserMultiSourceData selectLatestByUserIdAndSourceType(@Param("userId") String userId,
                                                          @Param("sourceType") String sourceType);

    // 查询用户全部多源数据记录
    List<UserMultiSourceData> selectByUserId(@Param("userId") String userId);

    // 新增数据记录
    int insert(UserMultiSourceData record);

    // 更新已有记录的数据载荷与状态
    int updateData(UserMultiSourceData record);
}
