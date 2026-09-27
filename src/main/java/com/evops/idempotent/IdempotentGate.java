package com.evops.idempotent;

/**
 * 入口幂等登记结果。
 */
public class IdempotentGate {

    public enum Outcome {
        /** 首次请求，已登记为 PROCESSING，放行 */
        ACCEPTED,
        /** 上次处理失败（业务事务已回滚），本次重试放行 */
        RETRY_ACCEPTED,
        /** 同一请求号正在处理中（并发重复提交） */
        IN_PROGRESS,
        /** 同一请求号已成功处理 */
        ALREADY_DONE
    }

    private final Outcome outcome;
    private final String existedResponseBody;

    private IdempotentGate(Outcome outcome, String existedResponseBody) {
        this.outcome = outcome;
        this.existedResponseBody = existedResponseBody;
    }

    public static IdempotentGate accepted() { return new IdempotentGate(Outcome.ACCEPTED, null); }
    public static IdempotentGate retryAccepted() { return new IdempotentGate(Outcome.RETRY_ACCEPTED, null); }
    public static IdempotentGate inProgress() { return new IdempotentGate(Outcome.IN_PROGRESS, null); }
    public static IdempotentGate alreadyDone(String responseBody) {
        return new IdempotentGate(Outcome.ALREADY_DONE, responseBody);
    }

    public Outcome getOutcome() { return outcome; }
    public String getExistedResponseBody() { return existedResponseBody; }
}
