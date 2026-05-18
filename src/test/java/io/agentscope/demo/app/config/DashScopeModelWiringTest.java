package io.agentscope.demo.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.demo.DashScopeSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class DashScopeModelWiringTest {

    @Autowired
    @Qualifier("formVisionDashScopeChatModel")
    private DashScopeChatModel formVisionModel;

    @Autowired
    @Qualifier("chatDashScopeChatModel")
    private DashScopeChatModel chatModel;

    @Autowired private DashScopeProperties dashScopeProperties;

    @Test
    void defaultVisionDoesNotEnableExtendedThinking() {
        assertThat(dashScopeProperties.isVisionEnableThinking()).isFalse();
    }

    @Test
    void springWiresExpectedDashScopeBeans() {
        assertThat(formVisionModel.getModelName()).isEqualTo("qwen3-vl-plus");
        assertThat(chatModel.getModelName()).isEqualTo("qwen-max");
    }

    /** 不发起 HTTP：仅确认关闭思考时本地构建不抛异常。 */
    @Test
    void visionModelWithThinkingOffBuilds() {
        DashScopeChatModel m = DashScopeSupport.visionModel("unit-test-key-not-sent", true, false);
        assertThat(m.getModelName()).isEqualTo("qwen3-vl-plus");
    }
}
