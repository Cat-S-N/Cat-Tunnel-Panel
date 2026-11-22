package com.admin;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 入口类注解自检（无外部 MySQL 依赖，确保异步与定时调度开启）。
 */
class AdminApplicationTests {

    @Test
    void applicationAnnotationsPresent() {
        assertTrue(AdminApplication.class.isAnnotationPresent(SpringBootApplication.class));
        assertTrue(AdminApplication.class.isAnnotationPresent(EnableAsync.class));
        assertTrue(AdminApplication.class.isAnnotationPresent(EnableScheduling.class));
    }
}
