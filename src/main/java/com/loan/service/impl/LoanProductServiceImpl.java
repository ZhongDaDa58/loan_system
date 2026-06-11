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

    @Override
    public LoanProductVO updateProduct(String productId, com.loan.entity.dto.LoanProductEditDTO editDTO) {
        // 校验产品是否存在（允许编辑已上架和已下架但通过此接口不能修改状态）
        LoanProduct existing = loanProductMapper.selectById(productId);
        if (existing == null) {
            throw new BusinessException(404, "产品不存在");
        }

        // apply editable fields if provided
        if (editDTO.getProductName() != null) existing.setProductName(editDTO.getProductName());
        if (editDTO.getMinAmount() != null) existing.setMinAmount(editDTO.getMinAmount());
        if (editDTO.getMaxAmount() != null) existing.setMaxAmount(editDTO.getMaxAmount());
        if (editDTO.getMinTerm() != null) existing.setMinTerm(editDTO.getMinTerm());
        if (editDTO.getMaxTerm() != null) existing.setMaxTerm(editDTO.getMaxTerm());
        if (editDTO.getInterestRate() != null) existing.setInterestRate(editDTO.getInterestRate());
        if (editDTO.getAutoPassScore() != null) existing.setAutoPassScore(editDTO.getAutoPassScore());
        if (editDTO.getManualReviewScore() != null) existing.setManualReviewScore(editDTO.getManualReviewScore());

        int updated = loanProductMapper.updateProduct(existing);
        if (updated != 1) {
            throw new BusinessException(500, "更新产品失败");
        }

        // 返回最新的详情视图
        return getProductDetail(productId);
    }

    @Override
    public LoanProductVO setProductStatus(String productId, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(400, "非法的状态值，只能为 0 或 1");
        }

        LoanProduct existing = loanProductMapper.selectById(productId);
        if (existing == null) {
            throw new BusinessException(404, "产品不存在");
        }

        int updated = loanProductMapper.updateProductStatus(productId, status);
        if (updated != 1) {
            throw new BusinessException(500, "更新产品状态失败");
        }

        return getProductDetail(productId);
    }

    @Override
    public LoanProductVO toggleProductStatus(String productId) {
        LoanProduct existing = loanProductMapper.selectById(productId);
        if (existing == null) {
            throw new BusinessException(404, "产品不存在");
        }

        Integer current = existing.getStatus();
        Integer next = (current != null && current == 1) ? 0 : 1;

        int updated = loanProductMapper.updateProductStatus(productId, next);
        if (updated != 1) {
            throw new BusinessException(500, "切换产品状态失败");
        }

        return getProductDetail(productId);
    }

    @Override
    public LoanProductVO createProduct(String userId, com.loan.entity.dto.LoanProductCreateDTO createDTO) {

        if (createDTO.getProductName() == null) {
            throw new BusinessException(400, "产品名称不能为空");
        }

        com.loan.entity.LoanProduct product = new com.loan.entity.LoanProduct();
        product.setProductId(com.loan.util.IdUtil.generateProductId());
        product.setProductName(createDTO.getProductName());
        product.setMinAmount(createDTO.getMinAmount());
        product.setMaxAmount(createDTO.getMaxAmount());
        product.setMinTerm(createDTO.getMinTerm());
        product.setMaxTerm(createDTO.getMaxTerm());
        product.setInterestRate(createDTO.getInterestRate());
        product.setAutoPassScore(createDTO.getAutoPassScore());
        product.setManualReviewScore(createDTO.getManualReviewScore());
        product.setStatus(1); // default to enabled when created
        java.util.Date now = new java.util.Date();
        product.setCreateTime(now);
        product.setUpdateTime(now);

        int rows = loanProductMapper.insertProduct(product);
        if (rows != 1) {
            throw new BusinessException(500, "新增产品失败");
        }

        // return created product view
        return getProductDetail(product.getProductId());
    }
}