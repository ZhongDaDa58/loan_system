package com.loan.service;
import com.loan.entity.LoanProduct;
import com.loan.entity.vo.LoanProductVO;

import java.util.List;

public interface LoanProductService {
    LoanProduct validateProduct(String productId);

    public List<LoanProductVO> getProductList();

    public LoanProductVO getProductDetail(String productId);
    
    /**
     * 更新产品信息（不允许通过此接口修改产品上下架状态）
     */
    public LoanProductVO updateProduct(String productId, com.loan.entity.dto.LoanProductEditDTO editDTO);
    
    /**
     * 设置产品上下架状态：status = 1 上架，status = 0 下架
     */
    public LoanProductVO setProductStatus(String productId, Integer status);

    /**
     * 切换产品状态：如果当前为上架(1)则切换为下架(0)，反之亦然；返回切换后的产品视图
     */
    public LoanProductVO toggleProductStatus(String productId);

    /**
     * 新增产品
     */
    public LoanProductVO createProduct(String userId, com.loan.entity.dto.LoanProductCreateDTO createDTO);
}