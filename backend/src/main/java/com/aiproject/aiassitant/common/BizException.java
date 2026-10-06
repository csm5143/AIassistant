package com.aiproject.aiassitant.common;

import lombok.Getter;

/**
 * Base business exception. All expected domain-level errors should throw this so the
 * global handler can convert them into a consistent R response.
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;
    private final String traceId;

    public BizException(String message) {
        super(message);
        this.code = R.CODE_FAIL;
        this.traceId = null;
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
        this.traceId = null;
    }

    public BizException(int code, String message, String traceId) {
        super(message);
        this.code = code;
        this.traceId = traceId;
    }

    public BizException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.traceId = null;
    }

    public static BizException unauthorized(String message) {
        return new BizException(R.CODE_UNAUTHORIZED, message);
    }

    public static BizException forbidden(String message) {
        return new BizException(R.CODE_FORBIDDEN, message);
    }

    public static BizException notFound(String message) {
        return new BizException(R.CODE_NOT_FOUND, message);
    }

    public static BizException guardBlocked(String message) {
        return new BizException(R.CODE_GUARD_BLOCKED, message);
    }
}
