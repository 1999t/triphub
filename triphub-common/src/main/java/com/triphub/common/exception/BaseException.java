package com.triphub.common.exception;

import com.triphub.common.result.ErrorCode;

/**
 * 统一的业务异常类型，由全局异常处理器转换为友好的错误响应。
 * <p>用于表示“业务规则不通过”等预期内错误，而不是系统级故障。</p>
 */
public class BaseException extends RuntimeException {

    /**
     * 业务错误码；为空时由上层使用通用错误码兜底。
     */
    private final Integer code;

    public BaseException() {
        super();
        this.code = ErrorCode.COMMON_ERROR.getCode();
    }

    public BaseException(String message) {
        super(message);
        this.code = ErrorCode.COMMON_ERROR.getCode();
    }

    public BaseException(ErrorCode errorCode) {
        super(errorCode.getMsg());
        this.code = errorCode.getCode();
    }

    public BaseException(String message, Throwable cause) {
        super(message, cause);
        this.code = ErrorCode.COMMON_ERROR.getCode();
    }

    public BaseException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }
}


