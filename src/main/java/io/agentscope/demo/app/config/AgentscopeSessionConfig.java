package io.agentscope.demo.app.config;

import io.agentscope.core.session.JsonSession;
import java.io.IOException;
import java.nio.file.Files;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AgentscopeProperties.class)
public class AgentscopeSessionConfig {

    @Bean
    public JsonSession jsonSession(AgentscopeProperties properties) throws IOException {
        var root = properties.resolvedSessionRoot();
        Files.createDirectories(root);
        return new JsonSession(root);
    }
}
