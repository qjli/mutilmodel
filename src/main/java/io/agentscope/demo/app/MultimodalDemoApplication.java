package io.agentscope.demo.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot 入口：扫描 {@code io.agentscope.demo} 下所有组件（含 {@code app} 与示例包中的 Bean）。
 *
 * <p>{@link EnableScheduling} 启用 Spring {@code @Scheduled} 等调度能力；本 demo 中 {@link
 * io.agentscope.demo.app.service.FileJobService} 的模拟进度另使用自建 {@link
 * java.util.concurrent.ScheduledExecutorService}。
 */
@SpringBootApplication(scanBasePackages = "io.agentscope.demo")
@EnableScheduling
public class MultimodalDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(MultimodalDemoApplication.class, args);
    }
}
