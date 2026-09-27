package com.evops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 强制 CGLIB 代理：业务 Service 以具体类注入（拦截器/控制器构造注入），
 * 避免 @Transactional 生成 JDK 动态代理导致按类型注入失败。
 */
@SpringBootApplication
@EnableTransactionManagement(proxyTargetClass = true)
@EnableScheduling
public class EvopsApplication {
    public static void main(String[] args) {
        SpringApplication.run(EvopsApplication.class, args);
    }
}
