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

    /**
     * 编辑产品信息（不允许通过此接口修改产品上下架状态）
     * 请求方式：PUT /api/v1/products/{productId}
     */
    @PutMapping("/{productId}")
    @Operation(summary = "编辑产品信息", description = "更新产品可编辑字段（不能切换上下架状态）")
    public Result<LoanProductVO> editProduct(@PathVariable String productId, @RequestBody com.loan.entity.dto.LoanProductEditDTO editDTO) {
        LoanProductVO updated = productService.updateProduct(productId, editDTO);
        return Result.success(updated);
    }

    /**
     * 新增产品（仅管理员可调用）
     * 请求：POST /api/v1/products
     */
    @PostMapping
    @Operation(summary = "新增产品", description = "创建新的贷款产品，productId 由服务端生成（格式 product_xxx）")
    public Result<LoanProductVO> createProduct(@RequestBody com.loan.entity.dto.LoanProductCreateDTO createDTO, jakarta.servlet.http.HttpServletRequest request) {
        Object role = request.getAttribute("role");
//        if (role == null || !"admin".equals(role.toString())) {
//            return Result.error(403, "无权限：仅管理员可新增产品");
//        }

        String userId = (String) request.getAttribute("userId");
        LoanProductVO created = productService.createProduct(userId, createDTO);
        return Result.success(created);
    }

    /**
     * 切换产品上下架状态：如果当前已上架则下架，反之上架。仅管理员可调用。
     * 请求：POST /api/v1/products/{productId}/toggle
     */
    @PostMapping("/{productId}/toggle")
    @Operation(summary = "切换产品上下架", description = "切换产品状态（上架<->下架），仅管理员可调用")
    public Result<LoanProductVO> toggleProduct(@PathVariable String productId, jakarta.servlet.http.HttpServletRequest request) {
        Object role = request.getAttribute("role");
        if (role == null || !"admin".equals(role.toString())) {
            return Result.error(403, "无权限：仅管理员可切换产品状态");
        }

        LoanProductVO updated = productService.toggleProductStatus(productId);
        return Result.success(updated);
    }
}
