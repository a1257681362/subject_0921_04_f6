package com.evops.idempotent;

/**
 * 请求号幂等登记服务。所有登记操作使用独立事务（REQUIRES_NEW）：
 * 业务事务回滚不影响 PROCESSING/FAILED 标记，成功提交后再置 DONE。
 */
public interface IdempotentService {

    /**
     * 入口登记：
     * 不存在 -> 写入 PROCESSING 放行；
     * PROCESSING -> 拒绝并发重复提交；
     * DONE -> 拒绝重复提交；
     * FAILED -> 上次业务已回滚，允许同请求号重试。
     */
    IdempotentGate register(String requestNo, String operator);

    void markDone(String requestNo);

    void markFailed(String requestNo);

    /** 测试/排障用 */
    long count();
}
