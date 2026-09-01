package dev.saseq.configs;

import net.dv8tion.jda.api.requests.GatewayIntent;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordMcpConfigTest {
    @Test
    void toolServiceInventoryMatchesTheInjectedExportSurface() {
        var discordToolsMethod = Arrays.stream(DiscordMcpConfig.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("discordTools"))
                .findFirst()
                .orElseThrow();
        Set<Class<?>> injectedServiceTypes = Arrays.stream(discordToolsMethod.getParameterTypes())
                .filter(type -> type != McpToolPolicy.class)
                .collect(Collectors.toSet());

        assertThat(injectedServiceTypes)
                .containsExactlyInAnyOrderElementsOf(DiscordMcpConfig.toolServiceTypes());
    }

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
