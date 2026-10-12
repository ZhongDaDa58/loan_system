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

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(LoanProductServiceImpl.class);

    @Resource
    private LoanProductMapper loanProductMapper;

    /** 阶段 2：产品阈值变更留痕 */
    @Resource
    private com.loan.mapper.ProductThresholdHistoryMapper productThresholdHistoryMapper;


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
    public List<LoanProductVO> getAllProducts() {
        List<LoanProductVO> products = loanProductMapper.selectAll();
        for (LoanProductVO product : products) {
            product.setAmountRange(product.getMinAmount(), product.getMaxAmount());
            product.setTermRange(product.getMinTerm(), product.getMaxTerm());
        }
        return products;
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

        // 阶段 2：记录变更前的阈值（用于判断是否需要写变更留痕）
        Integer previousAutoPass = existing.getAutoPassScore();
        Integer previousManualReview = existing.getManualReviewScore();

        // apply editable fields if provided
        if (editDTO.getProductName() != null) existing.setProductName(editDTO.getProductName());
        if (editDTO.getMinAmount() != null) existing.setMinAmount(editDTO.getMinAmount());
        if (editDTO.getMaxAmount() != null) existing.setMaxAmount(editDTO.getMaxAmount());
        if (editDTO.getMinTerm() != null) existing.setMinTerm(editDTO.getMinTerm());
        if (editDTO.getMaxTerm() != null) existing.setMaxTerm(editDTO.getMaxTerm());
        if (editDTO.getInterestRate() != null) existing.setInterestRate(editDTO.getInterestRate());
        if (editDTO.getAutoPassScore() != null) existing.setAutoPassScore(editDTO.getAutoPassScore());
        if (editDTO.getManualReviewScore() != null) existing.setManualReviewScore(editDTO.getManualReviewScore());
        if (editDTO.getTargetBadRate() != null) existing.setTargetBadRate(editDTO.getTargetBadRate());
        if (editDTO.getTargetPassRate() != null) existing.setTargetPassRate(editDTO.getTargetPassRate());

        // 阶段 1：阈值关系与范围校验（防止"人工审核线 > 自动通过线"导致人工档失效）
        validateThresholds(existing.getAutoPassScore(), existing.getManualReviewScore());

        // 阶段 2：阈值变化时标记来源与时间（管理端手工修改）
        boolean thresholdChanged = !java.util.Objects.equals(previousAutoPass, existing.getAutoPassScore())
                || !java.util.Objects.equals(previousManualReview, existing.getManualReviewScore());
        if (thresholdChanged) {
            existing.setThresholdSource("MANUAL");
            existing.setThresholdUpdatedAt(new java.util.Date());
        }

        int updated = loanProductMapper.updateProduct(existing);
        if (updated != 1) {
            throw new BusinessException(500, "更新产品失败");
        }

        // 阶段 2：写阈值变更历史（可追溯）
        if (thresholdChanged) {
            try {
                com.loan.entity.ProductThresholdHistory history = new com.loan.entity.ProductThresholdHistory();
                history.setProductId(existing.getProductId());
                history.setAutoPassScore(existing.getAutoPassScore());
                history.setManualReviewScore(existing.getManualReviewScore());
                history.setTargetBadRate(existing.getTargetBadRate());
                history.setThresholdSource(existing.getThresholdSource());
                history.setChangeReason("管理端更新产品阈值");
                history.setChangedBy("admin");
                history.setCreateTime(new java.util.Date());
                productThresholdHistoryMapper.insert(history);
            } catch (Exception e) {
                log.warn("⚠️ 阈值变更留痕写入失败（不影响产品更新）: {}", e.getMessage());
            }
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

        // 直接返回更新后的产品信息手动转 VO（不用 getProductDetail/selectByIdVO，它们会过滤已下架）
        LoanProduct p = loanProductMapper.selectById(productId);
        LoanProductVO vo = new LoanProductVO();
        vo.setProductId(p.getProductId());
        vo.setProductName(p.getProductName());
        vo.setMinAmount(p.getMinAmount());
        vo.setMaxAmount(p.getMaxAmount());
        vo.setMinTerm(p.getMinTerm());
        vo.setMaxTerm(p.getMaxTerm());
        vo.setInterestRate(p.getInterestRate());
        vo.setStatus(p.getStatus());
        vo.setAutoPassScore(p.getAutoPassScore());
        vo.setManualReviewScore(p.getManualReviewScore());
        vo.setCreateTime(p.getCreateTime());
        vo.setUpdateTime(p.getUpdateTime());
        vo.setAmountRange(p.getMinAmount(), p.getMaxAmount());
        vo.setTermRange(p.getMinTerm(), p.getMaxTerm());
        return vo;
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

        // 阶段 1：阈值关系与范围校验
        validateThresholds(product.getAutoPassScore(), product.getManualReviewScore());

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

    // ==================== 阶段 1：阈值校验 ====================

    /**
     * 产品阈值校验（阈值分化基础保障）：
     *  - 未配置（两者均为空）→ 允许，运行时回退全局默认阈值；
     *  - 仅配置其一 → 报错（必须成对配置）；
     *  - 范围：300–950；
     *  - 关系：manual_review_score ≤ auto_pass_score（否则人工审核档在判定逻辑中不可达）。
     */
    private void validateThresholds(Integer autoPass, Integer manualReview) {
        if (autoPass == null && manualReview == null) {
            return;
        }
        if (autoPass == null || manualReview == null) {
            throw new BusinessException(400, "自动通过线与人工审核线必须同时配置");
        }
        if (autoPass < 300 || autoPass > 950) {
            throw new BusinessException(400, "自动通过线须在 300–950 之间，当前：" + autoPass);
        }
        if (manualReview < 300 || manualReview > 950) {
            throw new BusinessException(400, "人工审核线须在 300–950 之间，当前：" + manualReview);
        }
        if (manualReview > autoPass) {
            throw new BusinessException(400,
                    "人工审核线不得高于自动通过线（人工=" + manualReview + "，自动=" + autoPass + "）");
        }
    }
}