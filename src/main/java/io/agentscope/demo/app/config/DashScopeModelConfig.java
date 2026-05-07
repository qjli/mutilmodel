package io.agentscope.demo.app.config;

import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.demo.DashScopeSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 声明本应用用到的 {@link DashScopeChatModel} Bean（文本对话与视觉识别各一枚，按名称区分注入）。
 *
 * <p>与 {@link DashScopeSupport} 静态工厂配合，将 {@link DashScopeProperties} 中的密钥与开关落到具体模型实例。
 */
@Configuration
@EnableConfigurationProperties(DashScopeProperties.class)
public class DashScopeModelConfig {

    private static final Logger log = LoggerFactory.getLogger(DashScopeModelConfig.class);

    /**
     * 文本对话：{@code qwen-max}，非流式，便于 {@link io.agentscope.core.agent.CallableAgent#call} 同步阻塞返回。
     */
    @Bean(name = "chatDashScopeChatModel")
    public DashScopeChatModel chatDashScopeChatModel(DashScopeProperties properties) {
        log.info("[dashscope] chat bean model=qwen-max stream=false");
        return DashScopeSupport.chatModel(properties.getApiKey(), "qwen-max", false);
    }

    /**
     * 多图表单识别：{@code qwen-vl-max}，流式，供 ReActAgent 流事件推送前端。
     *
     * <p><b>说明：</b>当前 DashScope 上 {@code qwen-vl-max} 与 extended thinking 不兼容：开启思考并传入正的
     * {@code thinking_budget} 会稳定返回 400 {@code thinking_budget ... not greater than 0}（即该模型不允许正
     * budget）。因此默认 {@code enableThinking=false}；流式中的推理片段依赖模型侧其它输出，而非 extended
     * thinking。
     *
     * <p>若将来更换为支持思考的视觉模型，可再通过配置打开 {@link DashScopeProperties#isVisionEnableThinking()}。
     */
    @Bean(name = "formVisionDashScopeChatModel")
    public DashScopeChatModel formVisionDashScopeChatModel(DashScopeProperties properties) {
        if (properties.isVisionEnableThinking()) {
            log.info(
                    "[dashscope] vision bean model=qwen-vl-max stream=true enableThinking=true thinkingBudget={}",
                    properties.getVisionThinkingBudget());
            return DashScopeSupport.visionModel(
                    properties.getApiKey(), true, true, properties.getVisionThinkingBudget());
        }
        log.info("[dashscope] vision bean model=qwen-vl-max stream=true enableThinking=false");
        return DashScopeSupport.visionModel(properties.getApiKey(), true, false);
    }
}
