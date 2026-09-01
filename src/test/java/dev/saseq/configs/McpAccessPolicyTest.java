package dev.saseq.configs;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpAccessPolicyTest {
    private static final String ALLOWED_GUILD = "12345678901234567";
    private static final String DENIED_GUILD = "22345678901234567";
    private static final String CHANNEL = "32345678901234567";
    private static final String OTHER_CHANNEL = "42345678901234567";

    @Test
    void unconfiguredPolicyPreservesTheFullProviderAndArguments() {
        AtomicReference<String> received = new AtomicReference<>();
        ToolCallback raw = callback("send_message", schema("channelId", "message"), received);
        McpAccessPolicy policy = policy(mock(JDA.class), "", "", "");

        ToolCallback exposed = only(policy.apply(ToolCallbackProvider.from(raw)));

        assertThat(exposed).isSameAs(raw);
        assertThat(exposed.call("{\"channelId\":123}")) .isEqualTo("called");
        assertThat(received).hasValue("{\"channelId\":123}");
    }

    @Test
    void exactToolAllowlistFiltersAndRejectsUnknownNames() {
        McpAccessPolicy policy = policy(mock(JDA.class), "", "read_messages", "");
        ToolCallbackProvider filtered = policy.apply(ToolCallbackProvider.from(
                callback("read_messages", schema("channelId"), new AtomicReference<>()),
                callback("send_message", schema("channelId", "message"), new AtomicReference<>())));

        assertThat(filtered.getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("read_messages");

        McpAccessPolicy invalid = policy(mock(JDA.class), "", "missing_tool", "");
        assertThatThrownBy(() -> invalid.apply(filtered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown tools")
                .hasMessageContaining("missing_tool");
    }

    @Test
    void channelTargetMustResolveToAnAllowedGuild() {
        JDA jda = mock(JDA.class);
        stubChannel(jda, CHANNEL, ALLOWED_GUILD);
        stubChannel(jda, OTHER_CHANNEL, DENIED_GUILD);
        AtomicReference<String> received = new AtomicReference<>();
        ToolCallbackProvider provider = ToolCallbackProvider.from(
                callback("send_message", schema("channelId", "message"), received));
        ToolCallback exposed = only(policy(jda, ALLOWED_GUILD, "send_message", "").apply(provider));

        String allowedArguments = "{\"channelId\":\"" + CHANNEL + "\",\"message\":\"hello\"}";
        assertThat(exposed.call(allowedArguments)).isEqualTo("called");
        assertThat(received).hasValue(allowedArguments);

        assertThatThrownBy(() -> exposed.call(
                "{\"channelId\":\"" + OTHER_CHANNEL + "\",\"message\":\"hello\"}"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("outside the allowed guild scope");
        assertThatThrownBy(() -> exposed.call(
                "{\"channelId\":\"52345678901234567\",\"message\":\"hello\"}"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("outside the allowed guild scope");
    }

    @Test
    void everySuppliedDeclaredTargetMustStayInsideTheAllowlist() {
        JDA jda = mock(JDA.class);
        stubChannel(jda, CHANNEL, ALLOWED_GUILD);
        stubChannel(jda, OTHER_CHANNEL, DENIED_GUILD);
        ToolCallback exposed = only(policy(jda, ALLOWED_GUILD, "move_channel", "")
                .apply(ToolCallbackProvider.from(callback("move_channel",
                        schema("guildId", "channelId", "categoryId"), new AtomicReference<>()))));

        assertThatThrownBy(() -> exposed.call("{\"guildId\":\"" + ALLOWED_GUILD
                + "\",\"channelId\":\"" + CHANNEL + "\",\"categoryId\":\""
                + OTHER_CHANNEL + "\"}"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("outside the allowed guild scope");
    }

    @Test
    void emptyOptionalCategoryDetachesAfterAnotherTargetEstablishesScope() {
        JDA jda = mock(JDA.class);
        stubChannel(jda, CHANNEL, ALLOWED_GUILD);
        AtomicReference<String> received = new AtomicReference<>();
        ToolCallback exposed = only(policy(jda, ALLOWED_GUILD, "move_channel", "")
                .apply(ToolCallbackProvider.from(callback("move_channel",
                        schema("channelId", "categoryId"), received))));

        String detachArguments = "{\"channelId\":\"" + CHANNEL
                + "\",\"categoryId\":\"\"}";
        assertThat(exposed.call(detachArguments)).isEqualTo("called");
        assertThat(received).hasValue(detachArguments);

        assertThatThrownBy(() -> exposed.call("{\"categoryId\":\"\"}"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("outside the allowed guild scope");
    }

    @Test
    void defaultGuildOnlyAppliesToToolsThatDeclareGuildId() {
        McpAccessPolicy policy = policy(mock(JDA.class), ALLOWED_GUILD,
                "list_channels", ALLOWED_GUILD);
        AtomicInteger calls = new AtomicInteger();
        ToolCallback exposed = only(policy.apply(ToolCallbackProvider.from(
                countingCallback("list_channels", schema("guildId"), calls))));

        assertThat(exposed.call("{}")).isEqualTo("called");
        assertThat(calls).hasValue(1);

        McpAccessPolicy channelPolicy = policy(mock(JDA.class), ALLOWED_GUILD,
                "read_messages", ALLOWED_GUILD);
        ToolCallback channelTool = only(channelPolicy.apply(ToolCallbackProvider.from(
                countingCallback("read_messages", schema("channelId"), calls))));
        assertThatThrownBy(() -> channelTool.call("{}"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void guildScopeHidesUnresolvableToolsUnlessTheyWereExplicitlyRequested() {
        ToolCallback global = callback("send_private_message", schema("userId", "message"),
                new AtomicReference<>());
        ToolCallback scoped = callback("send_message", schema("channelId", "message"),
                new AtomicReference<>());

        ToolCallbackProvider automatic = policy(mock(JDA.class), ALLOWED_GUILD, "", "")
                .apply(ToolCallbackProvider.from(global, scoped));
        assertThat(automatic.getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("send_message");

        McpAccessPolicy explicit = policy(mock(JDA.class), ALLOWED_GUILD,
                "send_private_message", "");
        assertThatThrownBy(() -> explicit.apply(ToolCallbackProvider.from(global, scoped)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no guild-resolvable target")
                .hasMessageContaining("send_private_message");
    }

    @Test
    void malformedConfigurationAndTargetTypesFailClosed() {
        assertThatThrownBy(() -> policy(mock(JDA.class), "not-an-id", "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("17-20 digit");
        assertThatThrownBy(() -> policy(mock(JDA.class), "00000000000000000", "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("17-20 digit");
        assertThatThrownBy(() -> policy(mock(JDA.class), ALLOWED_GUILD, "", DENIED_GUILD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be in DISCORD_MCP_ALLOWED_GUILDS");

        ToolCallback exposed = only(policy(mock(JDA.class), ALLOWED_GUILD,
                "list_channels", "").apply(ToolCallbackProvider.from(
                callback("list_channels", schema("guildId"), new AtomicReference<>()))));
        assertThatThrownBy(() -> exposed.call("{\"guildId\":12345678901234567}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON string");
        assertThatThrownBy(() -> exposed.call("[]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON object");
    }

    @Test
    void futureChannelShapedArgumentsReceiveGuildResolutionWithoutToolNameCatalogs() {
        JDA jda = mock(JDA.class);
        stubChannel(jda, CHANNEL, ALLOWED_GUILD);
        ToolCallback exposed = only(policy(jda, ALLOWED_GUILD,
                "future_forum_tool", "").apply(ToolCallbackProvider.from(
                callback("future_forum_tool", schema("destinationForumPostId"),
                        new AtomicReference<>()))));

        assertThat(exposed.call("{\"destinationForumPostId\":\"" + CHANNEL + "\"}"))
                .isEqualTo("called");
    }

    @Test
    void channelArgumentClassifierIgnoresUnrelatedObjectIds() {
        assertThat(McpAccessPolicy.isGuildChannelArgument("channelId")).isTrue();
        assertThat(McpAccessPolicy.isGuildChannelArgument("parentCategoryId")).isTrue();
        assertThat(McpAccessPolicy.isGuildChannelArgument("forumPostId")).isTrue();
        assertThat(McpAccessPolicy.isGuildChannelArgument("messageId")).isFalse();
        assertThat(McpAccessPolicy.isGuildChannelArgument("userId")).isFalse();
    }

    private static McpAccessPolicy policy(JDA jda, String guilds, String tools,
                                          String defaultGuild) {
        return new McpAccessPolicy(jda, new ObjectMapper(), guilds, tools, defaultGuild);
    }

    private static void stubChannel(JDA jda, String channelId, String guildId) {
        GuildChannel channel = mock(GuildChannel.class);
        Guild guild = mock(Guild.class);
        when(jda.getGuildChannelById(channelId)).thenReturn(channel);
        when(channel.getGuild()).thenReturn(guild);
        when(guild.getId()).thenReturn(guildId);
    }

    private static ToolCallback only(ToolCallbackProvider provider) {
        assertThat(provider.getToolCallbacks()).hasSize(1);
        return provider.getToolCallbacks()[0];
    }

    private static ToolCallback countingCallback(String name, String schema, AtomicInteger calls) {
        return new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder()
                    .name(name).description("test").inputSchema(schema).build();

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

    private static ToolCallback callback(String name, String schema,
                                         AtomicReference<String> received) {
        return new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder()
                    .name(name).description("test").inputSchema(schema).build();

            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String arguments) {
                received.set(arguments);
                return "called";
            }
        };
    }

    private static String schema(String... properties) {
        String body = Arrays.stream(properties)
                .map(name -> "\"" + name + "\":{\"type\":\"string\"}")
                .collect(Collectors.joining(","));
        return "{\"type\":\"object\",\"properties\":{" + body + "}}";
    }
}
