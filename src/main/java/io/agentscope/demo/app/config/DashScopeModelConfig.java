package io.agentscope.demo.app.config;

import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.demo.DashScopeSupport;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DashScopeProperties.class)
public class DashScopeModelConfig {

    /**
     * 文本对话使用的文本模型（非流式 + {@code agent.call} 阻塞），供 {@link
     * io.agentscope.demo.app.service.DemoChatService} 注入。
     */
    @Bean(name = "chatDashScopeChatModel")
    public DashScopeChatModel chatDashScopeChatModel(DashScopeProperties properties) {
        return DashScopeSupport.chatModel(properties.getApiKey(), "qwen-max", false);
    }

    /**
     * 表单视觉流式识别使用的 qwen-vl-max 模型。
     *
     * <p><b>说明：</b>当前 DashScope 上 {@code qwen-vl-max} 与 extended thinking 不兼容：开启思考并传入正的
     * {@code thinking_budget} 会稳定返回 400 {@code thinking_budget ... not greater than 0}（即该模型不允许正
     * budget）。因此此处固定为 {@code enableThinking=false}；流式中的推理片段依赖模型侧其它输出，而非
     * extended thinking。
     *
     * <p>若将来更换为支持思考的视觉模型，可再通过配置打开 {@link DashScopeProperties#isVisionEnableThinking()}。
     */
    @Bean(name = "formVisionDashScopeChatModel")
    public DashScopeChatModel formVisionDashScopeChatModel(DashScopeProperties properties) {
        if (properties.isVisionEnableThinking()) {
            return DashScopeSupport.visionModel(
                    properties.getApiKey(), true, true, properties.getVisionThinkingBudget());
        }
        return DashScopeSupport.visionModel(properties.getApiKey(), true, false);
    }
}
