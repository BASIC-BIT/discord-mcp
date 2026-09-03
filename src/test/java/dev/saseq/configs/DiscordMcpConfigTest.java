package dev.saseq.configs;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiscordMcpConfigTest {
    @Test
    void lateAccessPolicyValidationUsesSpringBootsNormalFailurePath() {
        ToolCallbackProvider rawProvider = mock(ToolCallbackProvider.class);
        when(rawProvider.getToolCallbacks()).thenReturn(new org.springframework.ai.tool.ToolCallback[0]);
        McpAccessPolicy policy = new McpAccessPolicy(mock(JDA.class), new ObjectMapper(),
                "12345678901234567", "missing_tool", "");
        assertThatThrownBy(() -> DiscordMcpConfig.applyAccessPolicy(policy, rawProvider))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown tools");
    }

    @Test
    void unexpectedToolSurfaceFailureUsesSpringBootsNormalFailurePath() {
        ToolCallbackProvider rawProvider = mock(ToolCallbackProvider.class);
        when(rawProvider.getToolCallbacks()).thenThrow(new IllegalArgumentException("broken surface"));
        McpAccessPolicy policy = new McpAccessPolicy(mock(JDA.class), new ObjectMapper(),
                "12345678901234567", "", "");
        PrintStream original = System.err;
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try {
            System.setErr(new PrintStream(output, true, StandardCharsets.UTF_8));
            assertThatThrownBy(() -> DiscordMcpConfig.applyAccessPolicy(policy, rawProvider))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("broken surface");
        } finally {
            System.setErr(original);
        }
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("ERROR: Discord tool access policy could not initialize: broken surface");
    }

    @Test
    void expectedBotIdentityIsOptionalButExactWhenConfigured() {
        assertThat(DiscordMcpConfig.normalizeExpectedBotId(null)).isNull();
        assertThat(DiscordMcpConfig.normalizeExpectedBotId("  ")).isNull();
        assertThat(DiscordMcpConfig.botIdMatches(null, "12345678901234567")).isTrue();
        assertThat(DiscordMcpConfig.normalizeExpectedBotId(" 012345678901234567 "))
                .isEqualTo("12345678901234567");
        assertThat(DiscordMcpConfig.botIdMatches("12345678901234567",
                "12345678901234567")).isTrue();
        assertThat(DiscordMcpConfig.botIdMatches("22345678901234567",
                "12345678901234567")).isFalse();
        assertThatThrownBy(() -> DiscordMcpConfig.normalizeExpectedBotId("not-an-id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonzero 17-20 digit");
        assertThatThrownBy(() -> DiscordMcpConfig.normalizeExpectedBotId("00000000000000000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonzero 17-20 digit");
        assertThatThrownBy(() -> DiscordMcpConfig.normalizeExpectedBotId("99999999999999999999"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonzero 17-20 digit");
    }

    @Test
    void gatewayIntentsStayAtTheCapabilitiesTheServerUses() {
        assertThat(DiscordMcpConfig.parseMessageContentOptIn(null)).isFalse();
        assertThat(DiscordMcpConfig.parseMessageContentOptIn("   ")).isFalse();
        assertThat(DiscordMcpConfig.parseMessageContentOptIn("FALSE")).isFalse();
        assertThat(DiscordMcpConfig.parseMessageContentOptIn(" True ")).isTrue();
        assertThatThrownBy(() -> DiscordMcpConfig.parseMessageContentOptIn("yes"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("true, false, or empty");

        assertThat(DiscordMcpConfig.requiredGatewayIntents(false)).containsExactlyInAnyOrder(
                GatewayIntent.GUILD_MEMBERS,
                GatewayIntent.GUILD_VOICE_STATES,
                GatewayIntent.SCHEDULED_EVENTS);
        assertThat(DiscordMcpConfig.requiredGatewayIntents(true)).containsExactlyInAnyOrder(
                GatewayIntent.GUILD_MEMBERS,
                GatewayIntent.GUILD_VOICE_STATES,
                GatewayIntent.MESSAGE_CONTENT,
                GatewayIntent.SCHEDULED_EVENTS);
    }
}
