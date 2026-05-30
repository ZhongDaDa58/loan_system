package com.loan.service.impl;
import com.loan.entity.LoanProduct;
import com.loan.entity.vo.LoanProductVO;
import com.loan.exception.BusinessException;
import com.loan.mapper.LoanProductMapper;
import com.loan.service.LoanProductService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
public class LoanProductServiceImpl implements LoanProductService {

    @Resource
    private LoanProductMapper loanProductMapper;


    @Override
    public LoanProduct validateProduct(String productId) {
        LoanProduct product = loanProductMapper.selectById(productId);
        if (product == null) {
            throw new BusinessException(404, "产品不存在或已下架");
        }
        if(product.getStatus() != 1){
            throw new BusinessException(400, "产品已下架");
        }

        return product;
    }
    @Override
    public List<LoanProductVO> getProductList() {
        List<LoanProductVO> products = loanProductMapper.selectAllEnabled();
        // 处理VO的展示字段（金额范围、期限范围）
        for (LoanProductVO product : products) {
            product.setAmountRange(product.getMinAmount(), product.getMaxAmount());
            product.setTermRange(product.getMinTerm(), product.getMaxTerm());
        }
        return products;
    }

    /**
     * 获取单个产品详情
     */
    @Override
    public LoanProductVO getProductDetail(String productId) {
        LoanProductVO product = loanProductMapper.selectByIdVO(productId);
        if (product == null) {
            throw new BusinessException(404, "产品不存在或已下架");
        }
        // 处理展示字段
        product.setAmountRange(product.getMinAmount(), product.getMaxAmount());
        product.setTermRange(product.getMinTerm(), product.getMaxTerm());
        return product;
    }
}