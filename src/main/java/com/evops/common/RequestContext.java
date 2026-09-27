package com.evops.common;

import java.time.LocalDateTime;

/**
 * 当前请求的业务上下文：操作人 / 业务时间 / 请求号。
 * 由 IdempotentInterceptor 在入口写入，Service 与 MetaObjectHandler 读取，请求结束清理。
 */
public final class RequestContext {

    private static final ThreadLocal<Ctx> HOLDER = new ThreadLocal<>();

    private RequestContext() {}

    public static void set(String operator, LocalDateTime bizTime, String requestNo) {
        HOLDER.set(new Ctx(operator, bizTime, requestNo));
    }

    public static Ctx get() {
        Ctx ctx = HOLDER.get();
        if (ctx == null) {
            throw new IllegalStateException("请求上下文未初始化");
        }
        return ctx;
    }

    public static String operator() { return get().operator; }
    public static LocalDateTime bizTime() { return get().bizTime; }
    public static String requestNo() { return get().requestNo; }

    public static void clear() { HOLDER.remove(); }

    public static class Ctx {
        private final String operator;
        private final LocalDateTime bizTime;
        private final String requestNo;

        public Ctx(String operator, LocalDateTime bizTime, String requestNo) {
            this.operator = operator;
            this.bizTime = bizTime;
            this.requestNo = requestNo;
        }

        public String getOperator() { return operator; }
        public LocalDateTime getBizTime() { return bizTime; }
        public String getRequestNo() { return requestNo; }
    }
}
