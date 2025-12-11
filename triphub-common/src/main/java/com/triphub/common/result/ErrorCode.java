package com.triphub.common.result;

/**
 * 简单的错误码枚举。
 * <p>先覆盖认证 / 登录相关的常见场景，后续需要可以再逐步扩展。</p>
 */
public enum ErrorCode {

    SUCCESS(0, "ok"),

    /** 通用业务错误（未细分场景时的兜底） */
    COMMON_ERROR(1, "error"),

    /** 请求参数不合法（为空等） */
    AUTH_INVALID_PARAM(1001, "请求参数不合法"),

    /** 手机号格式不正确 */
    AUTH_INVALID_PHONE(1002, "手机号格式不正确"),

    /** 验证码请求过于频繁 */
    AUTH_CODE_TOO_FREQUENT(1003, "验证码请求过于频繁，请稍后再试"),

    /** 验证码已过期或不存在 */
    AUTH_CODE_EXPIRED(1004, "验证码已失效，请重新获取"),

    /** 验证码不匹配 */
    AUTH_CODE_ERROR(1005, "验证码错误");

    private final int code;
    private final String msg;

    ErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }
}



