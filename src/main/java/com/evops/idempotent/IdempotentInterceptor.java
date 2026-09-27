package com.evops.idempotent;

import com.evops.common.ApiResponse;
import com.evops.common.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 重复提交入口拦截：写请求必须携带已在 RequestContext 中的请求号，
 * 经 IdempotentService 三态判定后放行或直接拒绝（HTTP 409）。
 * 业务成功后由 afterCompletion 置 DONE；抛异常则置 FAILED 以允许同请求号重试。
 */
@Component
public class IdempotentInterceptor implements HandlerInterceptor {

    public static final String ATTR_ACCEPTED = IdempotentInterceptor.class.getName() + ".ACCEPTED";

    private final IdempotentService idempotentService;
    private final ObjectMapper objectMapper;

    public IdempotentInterceptor(IdempotentService idempotentService, ObjectMapper objectMapper) {
        this.idempotentService = idempotentService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        String requestNo = RequestContext.requestNo();
        String operator = RequestContext.operator();

        IdempotentGate gate = idempotentService.register(requestNo, operator);
        switch (gate.getOutcome()) {
            case ACCEPTED:
            case RETRY_ACCEPTED:
                request.setAttribute(ATTR_ACCEPTED, Boolean.TRUE);
                return true;
            case IN_PROGRESS:
                writeReject(response, "请求正在处理中，请勿重复提交：" + requestNo);
                return false;
            case ALREADY_DONE:
                writeReject(response, "重复提交：请求号 " + requestNo + " 已处理完成");
                return false;
            default:
                writeReject(response, "重复提交：" + requestNo);
                return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (!Boolean.TRUE.equals(request.getAttribute(ATTR_ACCEPTED))) {
            return;
        }
        String requestNo = RequestContext.requestNo();
        if (requestNo == null) {
            return;
        }
        // 业务异常经 @ExceptionHandler 处理后此处 ex 可能为 null，故以响应状态码为准：
        // 4xx/5xx 视为业务失败（事务已回滚）置 FAILED，允许同请求号重试；2xx 置 DONE。
        if (ex == null && response.getStatus() < HttpServletResponse.SC_BAD_REQUEST) {
            idempotentService.markDone(requestNo);
        } else {
            idempotentService.markFailed(requestNo);
        }
    }

    private void writeReject(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_CONFLICT);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(message)));
    }
}
