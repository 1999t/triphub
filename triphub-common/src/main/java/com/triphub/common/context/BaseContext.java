package com.triphub.common.context;

/**
 * 保存当前登录用户/管理员的 ID（基于 ThreadLocal）。
 */
public class BaseContext {

    private static final ThreadLocal<Long> CURRENT_ID = new ThreadLocal<>();

    public static void setCurrentId(Long id) {
        CURRENT_ID.set(id);
    }

    public static Long getCurrentId() {
        return CURRENT_ID.get();
    }

    public static void clear() {
        CURRENT_ID.remove();
    }
}


