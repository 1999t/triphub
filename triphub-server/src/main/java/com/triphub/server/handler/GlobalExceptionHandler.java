package com.triphub.server.handler;

import com.triphub.common.exception.BaseException;
import com.triphub.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLIntegrityConstraintViolationException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BaseException.class)
    public Result<Void> handleBaseException(BaseException ex) {
        log.error("业务异常: {}", ex.getMessage());
        return Result.error(ex.getMessage());
    }

    @ExceptionHandler({SQLIntegrityConstraintViolationException.class, DuplicateKeyException.class})
    public Result<Void> handleSqlException(Exception ex) {
        log.error("SQL异常: {}", ex.getMessage());
        String msg = ex.getMessage();
        if (msg != null && msg.contains("Duplicate entry")) {
            // 简单解析重复键错误信息
            String[] parts = msg.split(" ");
            if (parts.length >= 3) {
                String value = parts[2].replace("'", "");
                return Result.error(value + " 已存在");
            }
        }
        return Result.error("数据库操作异常");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOtherException(Exception ex) {
        log.error("系统异常", ex);
        return Result.error("系统异常，请稍后重试");
    }
}


