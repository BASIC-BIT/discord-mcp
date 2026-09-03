package dev.saseq.configs;

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
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
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
        assertThat(exposed.call("{\"channelId\":123}")).isEqualTo("called");
        assertThat(received).hasValue("{\"channelId\":123}");
    }

    @Test
    void policyParserIsNotTheLayerThatRejectsTheDocumentedFiftyMebibyteUpload() {
        ObjectMapper mapper = McpAccessPolicy.createArgumentObjectMapper(new ObjectMapper());
        assertThat(mapper.tokenStreamFactory().streamReadConstraints().getMaxStringLength())
                .isEqualTo(McpAccessPolicy.MAX_ARGUMENT_STRING_CHARACTERS);
        assertThat(mapper.tokenStreamFactory().streamReadConstraints().getMaxDocumentLength())
                .isEqualTo(McpAccessPolicy.MAX_ARGUMENT_DOCUMENT_CHARACTERS);
    }

    @Test
    void ordinaryToolParserUsesATightStringLimit() {
        ObjectMapper mapper = McpAccessPolicy.createOrdinaryArgumentObjectMapper(
                new ObjectMapper());
        assertThat(mapper.tokenStreamFactory().streamReadConstraints().getMaxStringLength())
                .isEqualTo(McpAccessPolicy.MAX_ORDINARY_ARGUMENT_STRING_CHARACTERS);
        assertThat(mapper.tokenStreamFactory().streamReadConstraints().getMaxDocumentLength())
                .isEqualTo(McpAccessPolicy.MAX_ORDINARY_ARGUMENT_DOCUMENT_CHARACTERS);
    }

    @Test
    void ordinaryToolRejectsAnOversizedTargetStringBeforeDelegation() {
        JDA jda = mock(JDA.class);
        stubChannel(jda, CHANNEL, ALLOWED_GUILD);
        AtomicInteger calls = new AtomicInteger();
        ToolCallback exposed = only(policy(jda, ALLOWED_GUILD, "send_message", "")
                .apply(ToolCallbackProvider.from(countingCallback(
                        "send_message", schema("channelId", "message"), calls))));

        String arguments = "{\"channelId\":\""
                + "1".repeat(McpAccessPolicy.MAX_ORDINARY_ARGUMENT_STRING_CHARACTERS + 1)
                + "\",\"message\":\"hello\"}";
        assertThatThrownBy(() -> exposed.call(arguments))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum supported size");
        assertThat(calls).hasValue(0);
    }

    @Test
    void guildScopeRejectsDuplicateTargetKeysBeforeDelegation() {
        JDA jda = mock(JDA.class);
        stubChannel(jda, CHANNEL, ALLOWED_GUILD);
        AtomicReference<String> received = new AtomicReference<>();
        ToolCallback exposed = only(policy(jda, ALLOWED_GUILD, "send_message", "")
                .apply(ToolCallbackProvider.from(callback("send_message",
                        schema("channelId", "message"), received))));

        assertThatThrownBy(() -> exposed.call("{\"channelId\":\"" + CHANNEL
                + "\",\"channelId\":\"" + OTHER_CHANNEL + "\",\"message\":\"hello\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid JSON");
        assertThat(received.get()).isNull();
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
    void guildScopeComparesCanonicalSnowflakeValues() {
        JDA jda = mock(JDA.class);
        Guild guild = mock(Guild.class);
        AtomicReference<String> received = new AtomicReference<>();
        when(jda.getGuildById(ALLOWED_GUILD)).thenReturn(guild);
        when(guild.getId()).thenReturn(ALLOWED_GUILD);
        ToolCallback exposed = only(policy(jda, "0" + ALLOWED_GUILD,
                "list_channels", "0" + ALLOWED_GUILD)
                .apply(ToolCallbackProvider.from(callback("list_channels",
                        schema("guildId"), received))));

        String arguments = "{\"guildId\":\"0" + ALLOWED_GUILD + "\"}";
        assertThat(exposed.call(arguments)).isEqualTo("called");
        assertThat(received).hasValue(arguments);
    }

    @Test
    void guildIdOnlyToolDeniesANonAllowlistedGuild() {
        ToolCallback exposed = only(policy(mock(JDA.class), ALLOWED_GUILD,
                "list_channels", "").apply(ToolCallbackProvider.from(
                callback("list_channels", schema("guildId"), new AtomicReference<>()))));

        assertThatThrownBy(() -> exposed.call("{\"guildId\":\"" + DENIED_GUILD + "\"}"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("outside the allowed guild scope");
    }

    @Test
    void toolContextOverloadEnforcesScopeBeforeDelegation() {
        JDA jda = mock(JDA.class);
        stubChannel(jda, OTHER_CHANNEL, DENIED_GUILD);
        AtomicInteger calls = new AtomicInteger();
        ToolCallback exposed = only(policy(jda, ALLOWED_GUILD, "send_message", "")
                .apply(ToolCallbackProvider.from(countingCallback(
                        "send_message", schema("channelId", "message"), calls))));

        assertThatThrownBy(() -> exposed.call(
                "{\"channelId\":\"" + OTHER_CHANNEL + "\",\"message\":\"hello\"}",
                new ToolContext(Map.of())))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("outside the allowed guild scope");
        assertThat(calls).hasValue(0);
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
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target is required");
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
        assertThat(exposed.call("{\"guildId\":\"\"}")).isEqualTo("called");
        assertThat(calls).hasValue(2);

        McpAccessPolicy channelPolicy = policy(mock(JDA.class), ALLOWED_GUILD,
                "read_messages", ALLOWED_GUILD);
        ToolCallback channelTool = only(channelPolicy.apply(ToolCallbackProvider.from(
                countingCallback("read_messages", schema("channelId"), calls))));
        assertThatThrownBy(() -> channelTool.call("{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target is required");
    }

    @Test
    void credentialReturningWebhookToolsRequireExplicitOptInWhenGuildScoped() {
        ToolCallback createWebhook = callback("create_webhook", schema("channelId"),
                new AtomicReference<>());
        ToolCallback listWebhooks = callback("list_webhooks", schema("channelId"),
                new AtomicReference<>());

        ToolCallbackProvider defaultScoped = policy(mock(JDA.class), ALLOWED_GUILD, "", "")
                .apply(ToolCallbackProvider.from(createWebhook, listWebhooks));
        assertThat(defaultScoped.getToolCallbacks()).isEmpty();

        ToolCallbackProvider explicitScoped = policy(mock(JDA.class), ALLOWED_GUILD,
                "create_webhook", "").apply(ToolCallbackProvider.from(createWebhook));
        assertThat(explicitScoped.getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("create_webhook");
    }

    @Test
    void inviteCreationRequiresExplicitOptInWhenGuildScoped() {
        ToolCallback createInvite = callback("create_invite", schema("guildId", "channelId"),
                new AtomicReference<>());
        ToolCallback listInvites = callback("list_invites", schema("guildId"),
                new AtomicReference<>());

        assertThat(policy(mock(JDA.class), ALLOWED_GUILD, "", "")
                .apply(ToolCallbackProvider.from(createInvite, listInvites))
                .getToolCallbacks()).isEmpty();
        assertThat(policy(mock(JDA.class), ALLOWED_GUILD,
                "create_invite,list_invites", "")
                .apply(ToolCallbackProvider.from(createInvite, listInvites)).getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("create_invite", "list_invites");
    }

    @Test
    void guildScopedInviteCreationRequiresFiniteAgeAndUseBounds() {
        JDA jda = mock(JDA.class);
        stubChannel(jda, CHANNEL, ALLOWED_GUILD);
        AtomicReference<String> received = new AtomicReference<>();
        ToolCallback createInvite = callback("create_invite",
                schema("guildId", "channelId", "maxAge", "maxUses", "temporary", "unique"),
                received);
        ToolCallback exposed = only(policy(jda, ALLOWED_GUILD, "create_invite", "")
                .apply(ToolCallbackProvider.from(createInvite)));

        assertThatThrownBy(() -> exposed.call("{\"guildId\":\"" + ALLOWED_GUILD
                + "\",\"channelId\":\"" + CHANNEL + "\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAge")
                .hasMessageContaining("explicitly set");
        assertThatThrownBy(() -> exposed.call("{\"guildId\":\"" + ALLOWED_GUILD
                + "\",\"channelId\":\"" + CHANNEL
                + "\",\"maxAge\":\"0\",\"maxUses\":\"10\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAge")
                .hasMessageContaining("positive integer");
        assertThatThrownBy(() -> exposed.call("{\"guildId\":\"" + ALLOWED_GUILD
                + "\",\"channelId\":\"" + CHANNEL
                + "\",\"maxAge\":\"86400\",\"maxUses\":\"0\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxUses")
                .hasMessageContaining("positive integer");

        String bounded = "{\"guildId\":\"" + ALLOWED_GUILD
                + "\",\"channelId\":\"" + CHANNEL
                + "\",\"maxAge\":\"86400\",\"maxUses\":\"10\"}";
        assertThat(exposed.call(bounded)).isEqualTo("called");
        assertThat(received).hasValue(bounded);

        String numericBounds = "{\"guildId\":\"" + ALLOWED_GUILD
                + "\",\"channelId\":\"" + CHANNEL
                + "\",\"maxAge\":86400,\"maxUses\":10}";
        assertThat(exposed.call(numericBounds)).isEqualTo("called");
        assertThat(received.get()).contains("\"maxAge\":\"86400\"")
                .contains("\"maxUses\":\"10\"");
    }

    @Test
    void realCredentialToolSchemasRemainExplicitlyExportable() {
        ToolCallbackProvider scoped = policy(mock(JDA.class), ALLOWED_GUILD,
                "create_invite,list_invites,create_webhook,list_webhooks", "")
                .apply(realToolProvider());

        assertThat(Arrays.stream(scoped.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name()).sorted().toList())
                .containsExactly("create_invite", "create_webhook",
                        "list_invites", "list_webhooks");
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
        assertThatThrownBy(() -> policy(mock(JDA.class), "99999999999999999999", "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("17-20 digit");
        assertThatThrownBy(() -> policy(mock(JDA.class), "", "   ", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty entry");
        assertThatThrownBy(() -> policy(mock(JDA.class), ALLOWED_GUILD, "", DENIED_GUILD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be in DISCORD_MCP_ALLOWED_GUILDS");
        assertThatThrownBy(() -> policy(mock(JDA.class), ALLOWED_GUILD, "",
                " " + ALLOWED_GUILD + " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("surrounding whitespace");

        ToolCallback exposed = only(policy(mock(JDA.class), ALLOWED_GUILD,
                "list_channels", "").apply(ToolCallbackProvider.from(
                callback("list_channels", schema("guildId"), new AtomicReference<>()))));
        assertThatThrownBy(() -> exposed.call("{\"guildId\":12345678901234567}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON string");
        assertThatThrownBy(() -> exposed.call("[]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON object");
        assertThatThrownBy(() -> exposed.call("{\"guildId\":\"123456789012345678901\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 20 characters");
    }

    @Test
    void threadTargetsResolveThroughTheThreadChannelFallback() {
        JDA jda = mock(JDA.class);
        ThreadChannel thread = mock(ThreadChannel.class);
        Guild guild = mock(Guild.class);
        when(jda.getThreadChannelById(CHANNEL)).thenReturn(thread);
        when(thread.getGuild()).thenReturn(guild);
        when(guild.getId()).thenReturn(ALLOWED_GUILD);
        ToolCallback exposed = only(policy(jda, ALLOWED_GUILD, "read_messages", "")
                .apply(ToolCallbackProvider.from(callback("read_messages", schema("channelId"),
                        new AtomicReference<>()))));

        assertThat(exposed.call("{\"channelId\":\"" + CHANNEL + "\"}"))
                .isEqualTo("called");
    }

    @Test
    void futureChannelShapedArgumentsFailStartupReviewClosed() {
        assertThatThrownBy(() -> policy(mock(JDA.class), ALLOWED_GUILD,
                "future_forum_tool", "").apply(ToolCallbackProvider.from(
                callback("future_forum_tool", schema("destinationForumPostId"),
                        new AtomicReference<>()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unreviewed Discord channel targets")
                .hasMessageContaining("destinationForumPostId");
    }

    @Test
    void futureUnreviewedArgumentsFailStartupReviewClosed() {
        McpAccessPolicy defaultPolicy = policy(mock(JDA.class), ALLOWED_GUILD, "",
                ALLOWED_GUILD);
        ToolCallbackProvider omitted = defaultPolicy.apply(ToolCallbackProvider.from(
                callback("future_target_tool", schema("guildId", "parentId"),
                        new AtomicReference<>())));
        assertThat(omitted.getToolCallbacks()).isEmpty();

        McpAccessPolicy policy = policy(mock(JDA.class), ALLOWED_GUILD,
                "future_target_tool", ALLOWED_GUILD);

        assertThatThrownBy(() -> policy.apply(ToolCallbackProvider.from(
                callback("future_target_tool", schema("guildId", "parentId"),
                        new AtomicReference<>()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unreviewed arguments")
                .hasMessageContaining("parentId");

        assertThatThrownBy(() -> policy.apply(ToolCallbackProvider.from(
                callback("future_target_tool", schema("guildId", "targetId"),
                        new AtomicReference<>()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unreviewed arguments")
                .hasMessageContaining("targetId");

        assertThatThrownBy(() -> policy.apply(ToolCallbackProvider.from(
                callback("future_target_tool", schema("guildId", "destinationIds"),
                        new AtomicReference<>()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unreviewed arguments")
                .hasMessageContaining("destinationIds");

        assertThatThrownBy(() -> policy.apply(ToolCallbackProvider.from(
                callback("future_target_tool", schema("guildId", "id", "parent_id"),
                        new AtomicReference<>()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unreviewed arguments")
                .hasMessageContaining("id")
                .hasMessageContaining("parent_id");

        ToolCallbackProvider scalarAliasOmitted = policy(mock(JDA.class), ALLOWED_GUILD,
                "", "").apply(ToolCallbackProvider.from(callback(
                        "send_message", schema("channelId", "message", "inviteCode"),
                        new AtomicReference<>())));
        assertThat(scalarAliasOmitted.getToolCallbacks()).isEmpty();

        assertThatThrownBy(() -> policy(mock(JDA.class), ALLOWED_GUILD,
                "send_message", "").apply(ToolCallbackProvider.from(callback(
                        "send_message", schema("channelId", "message", "inviteCode"),
                        new AtomicReference<>()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unreviewed arguments")
                .hasMessageContaining("inviteCode");

        assertThatThrownBy(() -> policy(mock(JDA.class), ALLOWED_GUILD,
                "future_structured_tool", ALLOWED_GUILD).apply(ToolCallbackProvider.from(
                callback("future_structured_tool", structuredSchema("target", "object"),
                        new AtomicReference<>()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unreviewed structured arguments")
                .hasMessageContaining("target");
    }

    @Test
    void guildTargetsMustDeclareStringSchemaTypesAtStartup() {
        McpAccessPolicy policy = policy(mock(JDA.class), ALLOWED_GUILD,
                "send_message", ALLOWED_GUILD);

        assertThatThrownBy(() -> policy.apply(ToolCallbackProvider.from(callback(
                "send_message",
                schemaWithTypes("channelId", "integer", "message", "string"),
                new AtomicReference<>()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Discord target arguments must use JSON string schemas")
                .hasMessageContaining("channelId");
    }

    @Test
    void scopedStartupWarnsForMissingGuildsAndSummarizesExports() {
        JDA jda = mock(JDA.class);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.err;
        try {
            System.setErr(new PrintStream(output, true, StandardCharsets.UTF_8));
            ToolCallbackProvider scoped = policy(jda, ALLOWED_GUILD,
                    "get_server_info", ALLOWED_GUILD).apply(ToolCallbackProvider.from(
                            callback("get_server_info", schema("guildId"),
                                    new AtomicReference<>())));

            assertThat(scoped.getToolCallbacks()).hasSize(1);
        } finally {
            System.setErr(original);
        }

        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("WARNING: Discord allowed guilds not currently visible to the bot: ["
                        + ALLOWED_GUILD + "]")
                .contains("Discord guild scope exported tools: [get_server_info]");
    }

    @Test
    void channelArgumentClassifierIgnoresUnrelatedObjectIds() {
        assertThat(McpAccessPolicy.isGuildChannelArgument("channelId")).isTrue();
        assertThat(McpAccessPolicy.isGuildChannelArgument("parentCategoryId")).isTrue();
        assertThat(McpAccessPolicy.isGuildChannelArgument("forumPostId")).isTrue();
        assertThat(McpAccessPolicy.isGuildChannelArgument("messageId")).isFalse();
        assertThat(McpAccessPolicy.isGuildChannelArgument("userId")).isFalse();
    }

    @Test
    void realToolSurfaceRequiresExplicitGuildScopeReview() {
        ToolCallbackProvider realTools = realToolProvider();
        assertThat(McpAccessPolicy.reviewedToolNames())
                .isEqualTo(Arrays.stream(realTools.getToolCallbacks())
                        .map(callback -> callback.getToolDefinition().name())
                        .collect(Collectors.toSet()));
        ToolCallbackProvider scoped = policy(mock(JDA.class), ALLOWED_GUILD, "", "")
                .apply(realTools);
        assertThat(Arrays.stream(scoped.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name()).sorted().toList())
                .containsExactly(
                        "add_reaction", "assign_role", "ban_member", "create_category",
                        "create_emoji", "create_forum_channel", "create_forum_post",
                        "create_guild_scheduled_event", "create_role",
                        "create_stage_channel", "create_text_channel", "create_voice_channel",
                        "delete_category", "delete_channel",
                        "delete_channel_permission_overwrite", "delete_emoji",
                        "delete_guild_scheduled_event", "delete_message", "delete_role",
                        "disconnect_member", "download_attachment", "edit_category", "edit_emoji",
                        "edit_forum_channel", "edit_guild_scheduled_event", "edit_message",
                        "edit_role", "edit_text_channel", "edit_voice_channel", "find_category",
                        "find_channel", "get_attachment", "get_bans", "get_bot_info", "get_channel_info",
                        "get_emoji_details", "get_forum_channel_info",
                        "get_guild_scheduled_event_users", "get_member_by_id", "get_message",
                        "get_server_info", "get_user_id_by_name", "kick_member",
                        "list_active_threads", "list_channel_permission_overwrites",
                        "list_channels", "list_channels_in_category", "list_emojis",
                        "list_forum_channels", "list_forum_posts", "list_forum_tags",
                        "list_guild_scheduled_events", "list_roles",
                        "modify_forum_post", "modify_voice_state", "move_channel",
                        "move_member", "read_messages", "remove_reaction", "remove_role",
                        "remove_timeout", "search_members", "send_file", "send_message",
                        "set_guild_scheduled_event_image", "set_nickname", "timeout_member",
                        "unban_member", "upsert_member_channel_permissions",
                        "upsert_role_channel_permissions");
    }

    private static ToolCallbackProvider realToolProvider() {
        return MethodToolCallbackProvider.builder().toolObjects(
                mock(DiscordService.class), mock(MessageService.class), mock(UserService.class),
                mock(ChannelService.class), mock(CategoryService.class), mock(WebhookService.class),
                mock(ThreadService.class), mock(RoleService.class), mock(ModerationService.class),
                mock(VoiceChannelService.class), mock(ScheduledEventService.class),
                mock(InviteService.class), mock(ChannelPermissionService.class),
                mock(EmojiService.class), mock(ForumService.class)).build();
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

    private static String structuredSchema(String name, String type) {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"guildId\":{\"type\":\"string\"},"
                + "\"" + name + "\":{\"type\":\"" + type + "\"}}}";
    }

    private static String schemaWithTypes(String... namesAndTypes) {
        StringBuilder body = new StringBuilder();
        for (int index = 0; index < namesAndTypes.length; index += 2) {
            if (body.length() > 0) {
                body.append(',');
            }
            body.append('"').append(namesAndTypes[index]).append("\":{\"type\":\"")
                    .append(namesAndTypes[index + 1]).append("\"}");
        }
        return "{\"type\":\"object\",\"properties\":{" + body + "}}";
    }
}
