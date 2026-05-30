package com.loan.entity.vo;
import com.loan.exception.BusinessException;
import lombok.AllArgsConstructor;
import lombok.Data;


import java.beans.ConstructorProperties;

@Data
@AllArgsConstructor

public class Result<T> {
    private int code;    // 状态码（200=成功，其他=失败）
    private String msg;  // 描述信息
    private T data;      // 业务数据

    // 成功响应（带数据）
    public static <T> Result<T> success(T data) {
        return new Result<>(200, "操作成功", data);
    }

    // 成功响应（无数据）
    public static <T> Result<T> success() {
        return new Result<>(200, "操作成功", null);
    }

    // 失败响应（自定义错误码和信息）
    public static <T> Result<T> error(int code, String msg) {
        return new Result<>(code, msg, null);
    }

    // 失败响应（使用自定义业务异常）
    public static <T> Result<T> error(BusinessException e) {
        return new Result<>(e.getCode(), e.getMsg(), null);
    }
}