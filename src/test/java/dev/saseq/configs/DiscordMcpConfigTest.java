package dev.saseq.configs;

import net.dv8tion.jda.api.requests.GatewayIntent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscordMcpConfigTest {
    @Test
    void expectedBotIdentityIsOptionalButExactWhenConfigured() {
        assertThat(DiscordMcpConfig.botIdMatches("", "12345678901234567")).isTrue();
        assertThat(DiscordMcpConfig.botIdMatches(null, "12345678901234567")).isTrue();
        assertThat(DiscordMcpConfig.botIdMatches(" 12345678901234567 ",
                "12345678901234567")).isTrue();
        assertThat(DiscordMcpConfig.botIdMatches("22345678901234567",
                "12345678901234567")).isFalse();
        assertThatThrownBy(() -> DiscordMcpConfig.botIdMatches("not-an-id",
                "12345678901234567"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonzero 17-20 digit");
        assertThatThrownBy(() -> DiscordMcpConfig.botIdMatches("00000000000000000",
                "12345678901234567"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonzero 17-20 digit");
    }

    @Test
    void gatewayIntentsStayAtTheCapabilitiesTheServerUses() {
        assertThat(DiscordMcpConfig.requiredGatewayIntents()).containsExactlyInAnyOrder(
                GatewayIntent.GUILD_MEMBERS,
                GatewayIntent.GUILD_VOICE_STATES,
                GatewayIntent.MESSAGE_CONTENT,
                GatewayIntent.SCHEDULED_EVENTS);
    }
}
