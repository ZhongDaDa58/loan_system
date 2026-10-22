package com.loan.exception;
import com.loan.entity.vo.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器：捕获所有Controller层异常，统一返回格式
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // 处理自定义业务异常
    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusinessException(BusinessException e) {
        return Result.error(e);
    }

    // 处理参数校验异常（@Valid 注解触发）
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleValidException(MethodArgumentNotValidException e) {
        BindingResult bindingResult = e.getBindingResult();
        // 拼接所有参数错误信息
        StringBuilder errorMsg = new StringBuilder("参数校验失败：");
        for (FieldError fieldError : bindingResult.getFieldErrors()) {
            errorMsg.append(fieldError.getField())
                    .append("：")
                    .append(fieldError.getDefaultMessage())
                    .append("，");
        }
        String msg = errorMsg.substring(0, errorMsg.length() - 1);
        return Result.error(400, msg);
    }

    // 处理系统异常（兜底）
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<?> handleException(Exception e) {
        // ★ 阶段一修正：原为 e.printStackTrace()——只打 stdout，不进 logback、不带 traceId，
        //   生产无法回溯。兜底处理器没日志 = 线上 500 完全无从排查（压测时已踩过）。
        log.error("未捕获异常，已返回 500", e);
        return Result.error(500, "服务器内部错误，请稍后重试");
    }
}