package com.loan.mapper;
import com.loan.entity.LoanProduct;
import com.loan.entity.vo.LoanProductVO;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

public interface LoanProductMapper {
    // 根据产品ID查询（校验产品是否存在）
    LoanProduct selectById(@Param("productId") String productId);

    LoanProductVO selectByIdVO(@Param("productId") String productId);
    /**
     * 查询所有可用产品（状态为ENABLED）
     */
    List<LoanProductVO> selectAllEnabled();
    //查询年利率方法
    BigDecimal selectInterestRateByProductId(@Param("productId") String productId);
    
    /**
     * 更新产品信息（只更新可编辑字段，不修改 status）
     */
    int updateProduct(LoanProduct product);
    
    /**
     * 更新产品状态（0-下架，1-上架）
     */
    int updateProductStatus(@Param("productId") String productId, @Param("status") Integer status);

    /**
     * 插入新产品
     */
    int insertProduct(LoanProduct product);
}