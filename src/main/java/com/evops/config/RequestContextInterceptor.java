package com.evops.config;

import com.evops.common.RequestContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * 入口拦截：为所有写请求（POST/PUT/PATCH/DELETE）解析并校验
 * X-Operator 操作人、X-Request-No 请求号、X-Biz-Time 业务时间（可选，缺省取当前时间），
 * 写入 RequestContext 供后续填充与幂等拦截使用。
 */
@Component
public class RequestContextInterceptor implements HandlerInterceptor {

    public static final String H_OPERATOR = "X-Operator";
    public static final String H_REQUEST_NO = "X-Request-No";
    public static final String H_BIZ_TIME = "X-Biz-Time";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        boolean writeRequest = !"GET".equalsIgnoreCase(method) && !"OPTIONS".equalsIgnoreCase(method);

        String operator = request.getHeader(H_OPERATOR);
        String requestNo = request.getHeader(H_REQUEST_NO);
        if (writeRequest) {
            if (!StringUtils.hasText(operator)) {
                throw new IllegalArgumentException("缺少请求头 " + H_OPERATOR + "（操作人）");
            }
            if (!StringUtils.hasText(requestNo)) {
                throw new IllegalArgumentException("缺少请求头 " + H_REQUEST_NO + "（请求号）");
            }
        }
        LocalDateTime bizTime = null;
        String bizTimeRaw = request.getHeader(H_BIZ_TIME);
        if (StringUtils.hasText(bizTimeRaw)) {
            try {
                bizTime = LocalDateTime.parse(bizTimeRaw);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(H_BIZ_TIME + " 格式应为 yyyy-MM-dd'T'HH:mm:ss");
            }
        }
        if (StringUtils.hasText(operator) && StringUtils.hasText(requestNo)) {
            RequestContext.set(operator.trim(), bizTime, requestNo.trim());
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        RequestContext.clear();
    }
}
