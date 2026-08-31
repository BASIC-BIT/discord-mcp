package dev.saseq.configs;

import net.dv8tion.jda.api.requests.GatewayIntent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordMcpConfigTest {
    @Test
    void requiredIntentsIncludeMemberVoiceAndEventAccess() {
        assertThat(DiscordMcpConfig.requiredGatewayIntents()).containsExactlyInAnyOrder(
                GatewayIntent.GUILD_MEMBERS,
                GatewayIntent.GUILD_VOICE_STATES,
                GatewayIntent.SCHEDULED_EVENTS);
    }

    @Test
    void messageContentGatewayIntentIsNotRequestedForRestOnlyReads() {
        assertThat(DiscordMcpConfig.requiredGatewayIntents())
                .doesNotContain(GatewayIntent.MESSAGE_CONTENT);
    }

    @Test
    void expectedBotIdComparisonTrimsConfigurationAndAllowsUnsetPin() {
        assertThat(DiscordMcpConfig.botIdMatches(" 123 ", "123")).isTrue();
        assertThat(DiscordMcpConfig.botIdMatches("", "123")).isTrue();
        assertThat(DiscordMcpConfig.botIdMatches(null, "123")).isTrue();
        assertThat(DiscordMcpConfig.botIdMatches("456", "123")).isFalse();
    }
}
