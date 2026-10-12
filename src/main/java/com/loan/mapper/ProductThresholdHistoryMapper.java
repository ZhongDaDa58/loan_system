package com.loan.mapper;

import com.loan.entity.ProductThresholdHistory;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 产品阈值变更历史 Mapper（阶段 2）
 * 对应表：product_threshold_history
 */
public interface ProductThresholdHistoryMapper {

    /** 新增一次阈值变更记录 */
    int insert(ProductThresholdHistory record);

    /** 查询某产品的阈值变更历史（倒序） */
    List<ProductThresholdHistory> selectByProductId(@Param("productId") String productId,
                                                    @Param("limit") Integer limit);
}
