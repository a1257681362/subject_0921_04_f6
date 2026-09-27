package com.evops.common;

/**
 * 业务规则异常：找不到单据、状态不允许流转、物理删除被拒等。
 */
public class BizException extends RuntimeException {
    public BizException(String message) {
        super(message);
    }
}
