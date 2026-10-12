package com.loan.mapper;

import com.loan.entity.ProductThresholdSuggestion;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 产品阈值标定建议 Mapper（P0）
 * 对应表：product_threshold_suggestion
 */
public interface ProductThresholdSuggestionMapper {

    /** 新增一条标定建议 */
    int insert(ProductThresholdSuggestion record);

    /** 查询某产品最近一条建议 */
    ProductThresholdSuggestion selectLatestByProductId(@Param("productId") String productId);

    /** 查询某产品全部建议（倒序） */
    List<ProductThresholdSuggestion> selectByProductId(@Param("productId") String productId,
                                                       @Param("limit") Integer limit);

    /** 标记建议已采纳 */
    int markAdopted(@Param("id") Long id);
}
