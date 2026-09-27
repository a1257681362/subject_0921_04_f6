package com.evops.config;

import com.evops.idempotent.IdempotentInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final RequestContextInterceptor requestContextInterceptor;
    private final IdempotentInterceptor idempotentInterceptor;

    public WebMvcConfig(RequestContextInterceptor requestContextInterceptor,
                        IdempotentInterceptor idempotentInterceptor) {
        this.requestContextInterceptor = requestContextInterceptor;
        this.idempotentInterceptor = idempotentInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requestContextInterceptor)
                .addPathPatterns("/api/**")
                .order(1);
        registry.addInterceptor(idempotentInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/health")
                .order(2);
    }
}
