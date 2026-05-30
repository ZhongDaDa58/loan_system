package com.loan.controller;
import com.loan.entity.vo.LoanProductVO;
import com.loan.entity.vo.Result;
import com.loan.service.LoanProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "产品查询模块", description = "查询贷款产品列表，以及产品细节")
public class LoanProductController {

    @Resource
    private LoanProductService productService;

    /**
     * 查看产品列表
     * 请求方式：GET /api/products
     */
    @GetMapping
    @Operation(summary = "查看产品列表", description = "返回所有产品列表")
    public Result<List<LoanProductVO>> getProductList() {
        List<LoanProductVO> products = productService.getProductList();
        return Result.success( products);
    }

    /**
     * 查看单个产品详情
     * 请求方式：GET /api/products/{productId}
     */
    @GetMapping("/{productId}")
    @Operation(summary = "查看单个产品详情", description = "返回指定产品ID的产品详情")
    public Result<LoanProductVO> getProductDetail(@PathVariable String productId) {
        LoanProductVO product = productService.getProductDetail(productId);
        return Result.success( product);
    }
}
