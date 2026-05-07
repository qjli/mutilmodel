package io.agentscope.demo.app.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AgentscopeAsyncConfig {

    @Bean(name = "agentscopeTaskExecutor")
    public Executor agentscopeTaskExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(8);
        ex.setQueueCapacity(200);
        ex.setThreadNamePrefix("agentscope-sse-");
        ex.initialize();
        return ex;
    }
}
