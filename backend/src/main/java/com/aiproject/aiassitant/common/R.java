package com.aiproject.aiassitant.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * Unified API response envelope. All controllers must wrap their payloads in this type
 * to keep client-side handling consistent (success flag + code + data + trace id).
 */
@Data
@NoArgsConstructor
public class R<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final int CODE_SUCCESS = 0;
    public static final int CODE_FAIL = 1;
    public static final int CODE_UNAUTHORIZED = 401;
    public static final int CODE_FORBIDDEN = 403;
    public static final int CODE_NOT_FOUND = 404;
    public static final int CODE_GUARD_BLOCKED = 429;
    public static final int CODE_INTERNAL_ERROR = 500;

    private int code;
    private String message;
    private T data;
    private String traceId;
    private long timestamp = System.currentTimeMillis();

    public R(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> R<T> ok() {
        return new R<>(CODE_SUCCESS, "ok", null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(CODE_SUCCESS, "ok", data);
    }

    public static <T> R<T> ok(String message, T data) {
        return new R<>(CODE_SUCCESS, message, data);
    }

    public static <T> R<T> fail(int code, String message) {
        return new R<>(code, message, null);
    }

    public static <T> R<T> fail(String message) {
        return new R<>(CODE_FAIL, message, null);
    }

    public boolean isSuccess() {
        return code == CODE_SUCCESS;
    }
}
