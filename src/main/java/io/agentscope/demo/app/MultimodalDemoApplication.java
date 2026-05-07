package io.agentscope.demo.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "io.agentscope.demo")
@EnableScheduling
public class MultimodalDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(MultimodalDemoApplication.class, args);
    }
}
