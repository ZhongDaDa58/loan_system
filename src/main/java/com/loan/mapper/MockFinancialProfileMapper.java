package com.loan.mapper;

import com.loan.entity.MockFinancialProfile;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 模拟金融信息 Mapper
 * 对应表：mock_financial_profile
 */
public interface MockFinancialProfileMapper {

    /** 按匹配键查询（身份证或手机号） */
    MockFinancialProfile selectByMatchKey(@Param("matchKey") String matchKey);

    /** 查询全部（管理端） */
    List<MockFinancialProfile> selectAll();

    /** 新增 */
    int insert(MockFinancialProfile record);

    /** 按匹配键更新 */
    int updateByMatchKey(MockFinancialProfile record);

    /** 删除 */
    int deleteById(@Param("id") Long id);

    /** 统计 */
    int countAll();
}
