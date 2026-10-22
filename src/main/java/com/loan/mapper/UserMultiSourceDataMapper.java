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

    /**
     * 统计「同一设备指纹」关联的**其它用户数**（第一层：设备指纹跨账号聚集检测，阶段 C5）
     *
     * <p>此前 device_fingerprint_hash 只被脱敏展示、不参与任何校验（缺口 G2），
     * L1-02「设备黑名单」读的是 Provider 随机生成的字段。本方法让指纹真正可用。
     *
     * @param userId      当前用户（排除自身）
     * @param fingerprint 设备指纹哈希（来自多源 device 块）
     * @return 关联的其他用户数；无指纹或无数据时返回 0
     */
    int countOtherUsersByDeviceFingerprint(@Param("userId") String userId,
                                           @Param("fingerprint") String fingerprint);
}
