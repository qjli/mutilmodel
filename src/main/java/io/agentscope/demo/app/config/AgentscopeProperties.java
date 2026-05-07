package io.agentscope.demo.app.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentscope")
public class AgentscopeProperties {

    /** Root directory for {@link io.agentscope.core.session.JsonSession} persistence. */
    private String sessionRoot = "data/agentscope-sessions";

    public String getSessionRoot() {
        return sessionRoot;
    }

    public void setSessionRoot(String sessionRoot) {
        this.sessionRoot = sessionRoot;
    }

    public Path resolvedSessionRoot() {
        return Path.of(sessionRoot).toAbsolutePath().normalize();
    }
}
