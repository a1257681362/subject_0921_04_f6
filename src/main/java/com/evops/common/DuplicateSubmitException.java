package com.evops.common;

/**
 * 重复提交异常：入口幂等拦截器发现 request_no 已被使用时抛出。
 */
public class DuplicateSubmitException extends RuntimeException {
    public DuplicateSubmitException(String message) {
        super(message);
    }
}
