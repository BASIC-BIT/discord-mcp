package dev.saseq.configs;

import net.dv8tion.jda.api.requests.GatewayIntent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordMcpConfigTest {
    @Test
    void requiredIntentsIncludeMemberAndMessageReadAccess() {
        assertThat(DiscordMcpConfig.requiredGatewayIntents(true)).containsExactlyInAnyOrder(
                GatewayIntent.GUILD_MEMBERS,
                GatewayIntent.MESSAGE_CONTENT,
                GatewayIntent.GUILD_VOICE_STATES,
                GatewayIntent.SCHEDULED_EVENTS);
    }

    @Test
    void messageContentIntentIsOptInForExistingDeployments() {
        assertThat(DiscordMcpConfig.requiredGatewayIntents(false))
                .contains(GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_VOICE_STATES,
                        GatewayIntent.SCHEDULED_EVENTS)
                .doesNotContain(GatewayIntent.MESSAGE_CONTENT);
    }

    @Test
    void privilegedIntentHintMatchesRequestedIntents() {
        assertThat(DiscordMcpConfig.privilegedIntentsForHint(false))
                .isEqualTo("'Server Members Intent'");
        assertThat(DiscordMcpConfig.privilegedIntentsForHint(true))
                .isEqualTo("'Server Members Intent' and 'Message Content Intent'");
    }
}
