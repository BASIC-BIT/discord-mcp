package dev.saseq.configs;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import dev.saseq.services.CategoryService;
import dev.saseq.services.ChannelPermissionService;
import dev.saseq.services.ChannelService;
import dev.saseq.services.DiscordService;
import dev.saseq.services.EmojiService;
import dev.saseq.services.ForumService;
import dev.saseq.services.InviteService;
import dev.saseq.services.MessageService;
import dev.saseq.services.ModerationService;
import dev.saseq.services.RoleService;
import dev.saseq.services.ScheduledEventService;
import dev.saseq.services.ThreadService;
import dev.saseq.services.UserService;
import dev.saseq.services.VoiceChannelService;
import dev.saseq.services.WebhookService;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

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
        McpToolPolicy policy = policy(jdaWithChannel(), ALLOWED_GUILD, "send_message", "preview", "");
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
    void defaultGuildDoesNotAuthorizeToolWithoutGuildTarget() {
        McpToolPolicy policy = new McpToolPolicy(mock(JDA.class), new ObjectMapper(),
                ALLOWED_GUILD, "send_webhook_message", ALLOWED_GUILD, "allow", "", 10485760);

        assertThatThrownBy(() -> only(policy.apply(provider("send_webhook_message", new AtomicInteger())))
                .call("{\"webhookUrl\":\"https://discord.com/api/webhooks/1/x\"}"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("could not be resolved");
    }

    @Test
    void suppliedButUnknownChannelFailsClosed() {
        McpToolPolicy policy = policy(mock(JDA.class), ALLOWED_GUILD, "read_messages", "allow", "");

        assertThatThrownBy(() -> only(policy.apply(provider("read_messages", new AtomicInteger())))
                .call("{\"guildId\":\"" + ALLOWED_GUILD
                        + "\",\"channelId\":\"32345678901234567\"}"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("channelId could not be resolved");
    }

    @Test
    void threadChannelIsResolvedToItsGuild() {
        JDA jda = mock(JDA.class);
        var thread = mock(net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel.class);
        Guild guild = mock(Guild.class);
        when(jda.getThreadChannelById("32345678901234567")).thenReturn(thread);
        when(thread.getGuild()).thenReturn(guild);
        when(guild.getId()).thenReturn(ALLOWED_GUILD);
        McpToolPolicy policy = policy(jda, ALLOWED_GUILD, "read_messages", "allow", "");

        assertThat(only(policy.apply(provider("read_messages", new AtomicInteger())))
                .call("{\"threadId\":\"32345678901234567\"}"))
                .isEqualTo("called");
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
        McpToolPolicy policy = policy(jdaWithChannel(), ALLOWED_GUILD, "send_message", "preview",
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

    @Test
    void completedCallIsNotReportedAsFailedWhenCompletionAuditFails() throws Exception {
        Path audit = tempDir.resolve("audit.jsonl");
        McpToolPolicy policy = policy(mock(JDA.class), ALLOWED_GUILD, "send_message", "allow",
                audit.toString());
        ToolDefinition definition = ToolDefinition.builder().name("send_message")
                .description("test").inputSchema("{\"type\":\"object\"}").build();
        ToolCallback callback = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String arguments) {
                try {
                    Files.delete(audit);
                    Files.createDirectory(audit);
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
                return "posted";
            }
        };

        String result = only(policy.apply(ToolCallbackProvider.from(callback)))
                .call("{\"guildId\":\"" + ALLOWED_GUILD + "\"}");

        assertThat(result).startsWith("posted")
                .contains("audit completion record failed");
    }

    @Test
    void everyReadOnlyClassificationNamesAnExistingTool() {
        Set<String> actualTools = Arrays.stream(new Class<?>[]{
                        DiscordService.class, MessageService.class, UserService.class,
                        ChannelService.class, CategoryService.class, WebhookService.class,
                        ThreadService.class, RoleService.class, ModerationService.class,
                        VoiceChannelService.class, ScheduledEventService.class, InviteService.class,
                        ChannelPermissionService.class, EmojiService.class, ForumService.class
                })
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .map(method -> method.getAnnotation(Tool.class))
                .filter(annotation -> annotation != null)
                .map(Tool::name)
                .collect(Collectors.toSet());

        assertThat(actualTools).containsAll(McpToolPolicy.readOnlyToolNames());
    }

    private static McpToolPolicy policy(JDA jda, String guilds, String tools, String mode, String audit) {
        return new McpToolPolicy(jda, new ObjectMapper(), guilds, tools, "", mode, audit, 10485760);
    }

    private static JDA jdaWithChannel() {
        JDA jda = mock(JDA.class);
        GuildChannel channel = mock(GuildChannel.class);
        Guild guild = mock(Guild.class);
        when(jda.getGuildChannelById("32345678901234567")).thenReturn(channel);
        when(channel.getGuild()).thenReturn(guild);
        when(guild.getId()).thenReturn(ALLOWED_GUILD);
        return jda;
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
