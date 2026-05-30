package com.loan.service;
import com.loan.entity.LoanProduct;
import com.loan.entity.vo.LoanProductVO;

import java.util.List;

public interface LoanProductService {
    LoanProduct validateProduct(String productId);

    public List<LoanProductVO> getProductList();

    public LoanProductVO getProductDetail(String productId);
}