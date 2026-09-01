package dev.saseq.configs;

import net.dv8tion.jda.api.requests.GatewayIntent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscordMcpConfigTest {
    @Test
    void expectedBotIdentityIsOptionalButExactWhenConfigured() {
        assertThat(DiscordMcpConfig.normalizeExpectedBotId(null)).isNull();
        assertThat(DiscordMcpConfig.normalizeExpectedBotId("  ")).isNull();
        assertThat(DiscordMcpConfig.botIdMatches(null, "12345678901234567")).isTrue();
        assertThat(DiscordMcpConfig.normalizeExpectedBotId(" 12345678901234567 "))
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
    }

    @Test
    void gatewayIntentsStayAtTheCapabilitiesTheServerUses() {
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
