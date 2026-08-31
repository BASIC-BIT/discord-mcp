package dev.saseq.configs;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpToolPolicyTest {
    private static final String ALLOWED_GUILD = "12345678901234567";
    private static final String DENIED_GUILD = "22345678901234567";

    @TempDir
    Path tempDir;

    @Test
    void previewReturnsArgumentsWithoutCallingWriteTool() {
        AtomicInteger calls = new AtomicInteger();
        McpToolPolicy policy = policy(mock(JDA.class), ALLOWED_GUILD, "send_message", "preview", "");
        ToolCallback callback = only(policy.apply(provider("send_message", calls)));

        String result = callback.call("{\"channelId\":\"32345678901234567\",\"message\":\"exact copy\",\"guildId\":\""
                + ALLOWED_GUILD + "\"}");

        assertThat(result).startsWith("WRITE_PREVIEW: send_message was not called.")
                .contains("exact copy");
        assertThat(calls).hasValue(0);
    }

    @Test
    void readToolRunsForAllowedGuild() {
        AtomicInteger calls = new AtomicInteger();
        McpToolPolicy policy = policy(mock(JDA.class), ALLOWED_GUILD, "read_messages", "preview", "");
        ToolCallback callback = only(policy.apply(provider("read_messages", calls)));

        assertThat(callback.call("{\"guildId\":\"" + ALLOWED_GUILD + "\"}"))
                .isEqualTo("called");
        assertThat(calls).hasValue(1);
    }

    @Test
    void deniedGuildNeverReachesTool() {
        AtomicInteger calls = new AtomicInteger();
        McpToolPolicy policy = policy(mock(JDA.class), ALLOWED_GUILD, "read_messages", "allow", "");
        ToolCallback callback = only(policy.apply(provider("read_messages", calls)));

        assertThatThrownBy(() -> callback.call("{\"guildId\":\"" + DENIED_GUILD + "\"}"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("not in DISCORD_MCP_ALLOWED_GUILDS");
        assertThat(calls).hasValue(0);
    }

    @Test
    void channelIdIsResolvedToItsGuild() {
        JDA jda = mock(JDA.class);
        GuildChannel channel = mock(GuildChannel.class);
        Guild guild = mock(Guild.class);
        when(jda.getGuildChannelById("32345678901234567")).thenReturn(channel);
        when(channel.getGuild()).thenReturn(guild);
        when(guild.getId()).thenReturn(ALLOWED_GUILD);
        AtomicInteger calls = new AtomicInteger();
        McpToolPolicy policy = policy(jda, ALLOWED_GUILD, "read_messages", "allow", "");

        assertThat(only(policy.apply(provider("read_messages", calls)))
                .call("{\"channelId\":\"32345678901234567\"}"))
                .isEqualTo("called");
        assertThat(calls).hasValue(1);
    }

    @Test
    void unresolvedGuildFailsClosedWhenAllowlistIsConfigured() {
        McpToolPolicy policy = policy(mock(JDA.class), ALLOWED_GUILD, "read_messages", "allow", "");

        assertThatThrownBy(() -> only(policy.apply(provider("read_messages", new AtomicInteger())))
                .call("{}"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("could not be resolved");
    }

    @Test
    void configuredToolListFiltersTheExportedSurface() {
        McpToolPolicy policy = policy(mock(JDA.class), "", "read_messages", "allow", "");
        ToolCallbackProvider raw = ToolCallbackProvider.from(
                callback("read_messages", new AtomicInteger()),
                callback("send_message", new AtomicInteger()));

        assertThat(policy.apply(raw).getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("read_messages");
    }

    @Test
    void auditRecordsIdentifiersAndHashButNotMessageBody() throws Exception {
        Path audit = tempDir.resolve("audit.jsonl");
        McpToolPolicy policy = policy(mock(JDA.class), ALLOWED_GUILD, "send_message", "preview",
                audit.toString());

        only(policy.apply(provider("send_message", new AtomicInteger())))
                .call("{\"guildId\":\"" + ALLOWED_GUILD
                        + "\",\"channelId\":\"32345678901234567\",\"message\":\"secret copy\"}");

        String line = Files.readString(audit);
        assertThat(line).contains("\"tool\":\"send_message\"")
                .contains("\"channelId\":\"32345678901234567\"")
                .contains("argumentsSha256")
                .doesNotContain("secret copy");
    }

    private static McpToolPolicy policy(JDA jda, String guilds, String tools, String mode, String audit) {
        return new McpToolPolicy(jda, new ObjectMapper(), guilds, tools, "", mode, audit);
    }

    private static ToolCallbackProvider provider(String name, AtomicInteger calls) {
        return ToolCallbackProvider.from(callback(name, calls));
    }

    private static ToolCallback only(ToolCallbackProvider provider) {
        assertThat(provider.getToolCallbacks()).hasSize(1);
        return provider.getToolCallbacks()[0];
    }

    private static ToolCallback callback(String name, AtomicInteger calls) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description("test")
                .inputSchema("{\"type\":\"object\"}")
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String arguments) {
                calls.incrementAndGet();
                return "called";
            }
        };
    }
}
