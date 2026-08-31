package dev.saseq.configs;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
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

        String result = callback.call("{\"channelId\":\"32345678901234567\",\"message\":\"exact copy\"}");

        assertThat(result).startsWith("WRITE_PREVIEW: send_message was not called.")
                .contains("exact copy");
        assertThat(calls).hasValue(0);
    }

    @Test
    void readToolRunsForAllowedGuild() {
        AtomicInteger calls = new AtomicInteger();
        McpToolPolicy policy = policy(jdaWithChannel(), ALLOWED_GUILD, "read_messages", "preview", "");
        ToolCallback callback = only(policy.apply(provider("read_messages", calls)));

        assertThat(callback.call("{\"channelId\":\"32345678901234567\"}"))
                .isEqualTo("called");
        assertThat(calls).hasValue(1);
    }

    @Test
    void deniedGuildNeverReachesTool() {
        AtomicInteger calls = new AtomicInteger();
        McpToolPolicy policy = policy(jdaWithChannel(DENIED_GUILD), ALLOWED_GUILD, "read_messages", "allow", "");
        ToolCallback callback = only(policy.apply(provider("read_messages", calls)));

        assertThatThrownBy(() -> callback.call("{\"channelId\":\"32345678901234567\"}"))
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
                ALLOWED_GUILD, "send_webhook_message", ALLOWED_GUILD, "allow", "", "10485760");

        assertThatThrownBy(() -> only(policy.apply(provider("send_webhook_message", new AtomicInteger())))
                .call("{\"webhookUrl\":\"https://discord.com/api/webhooks/1/x\"}"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("could not be resolved");
    }

    @Test
    void undeclaredGuildIdCannotBeUsedAsAWebhookDecoy() {
        McpToolPolicy policy = policy(mock(JDA.class), ALLOWED_GUILD, "send_webhook_message", "allow", "");

        assertThatThrownBy(() -> only(policy.apply(provider("send_webhook_message", new AtomicInteger())))
                .call("{\"webhookUrl\":\"https://discord.com/api/webhooks/1/x\","
                        + "\"message\":\"x\",\"guildId\":\"" + ALLOWED_GUILD + "\"}"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("undeclared arguments");
    }

    @Test
    void policyUsesTheGeneratedSpringToolSchemaForArguments() {
        McpToolPolicy policy = policy(jdaWithChannel(), ALLOWED_GUILD, "send_message", "preview", "");
        ToolCallbackProvider generated = MethodToolCallbackProvider.builder()
                .toolObjects(new GeneratedSchemaTool())
                .build();

        assertThatThrownBy(() -> only(policy.apply(generated)).call(
                "{\"channelId\":\"32345678901234567\",\"message\":\"x\",\"guildId\":\""
                        + ALLOWED_GUILD + "\"}"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("undeclared arguments");
    }

    @Test
    void numericChannelIdIsRejectedRatherThanSkipped() {
        McpToolPolicy policy = policy(mock(JDA.class), ALLOWED_GUILD, "send_message", "allow", "");

        assertThatThrownBy(() -> only(policy.apply(provider("send_message", new AtomicInteger())))
                .call("{\"channelId\":32345678901234567,\"message\":\"x\"}"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("must be a JSON string");
    }

    @Test
    void numericGuildIdIsRejectedRatherThanReplacedByDefault() {
        McpToolPolicy policy = new McpToolPolicy(mock(JDA.class), new ObjectMapper(),
                ALLOWED_GUILD, "list_channels", ALLOWED_GUILD, "allow", "", "10485760");

        assertThatThrownBy(() -> only(policy.apply(provider("list_channels", new AtomicInteger())))
                .call("{\"guildId\":32345678901234567}"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("guildId must be a string");
    }

    @Test
    void numericIdsPreserveLegacyBindingWhenNoPolicyIsConfigured() {
        AtomicInteger calls = new AtomicInteger();
        McpToolPolicy policy = policy(mock(JDA.class), "", "", "allow", "");

        assertThat(only(policy.apply(provider("send_message", calls)))
                .call("{\"channelId\":32345678901234567,\"message\":\"x\"}"))
                .isEqualTo("called");
        assertThat(calls).hasValue(1);
    }

    @Test
    void unconfiguredPolicyForwardsArgumentsUnchanged() {
        AtomicReference<String> received = new AtomicReference<>();
        ToolDefinition definition = ToolDefinition.builder().name("send_message")
                .description("test").inputSchema(schema("channelId", "message")).build();
        ToolCallback raw = new ToolCallback() {
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
        McpToolPolicy policy = policy(mock(JDA.class), "", "", "allow", "");
        String arguments = "{ \"message\" : \"x\", \"channelId\" : 32345678901234567 }";

        assertThat(only(policy.apply(ToolCallbackProvider.from(raw))).call(arguments))
                .isEqualTo("called");
        assertThat(received).hasValue(arguments);
    }

    @Test
    void invalidLegacyDefaultGuildDoesNotBlockStartupWithoutPolicy() {
        McpToolPolicy policy = new McpToolPolicy(mock(JDA.class), new ObjectMapper(),
                "", "", "OPTIONAL_DEFAULT_SERVER_ID", "allow", "", "not-an-integer");

        assertThat(only(policy.apply(provider("list_channels", new AtomicInteger())))
                .call("{\"guildId\":\"123\"}"))
                .isEqualTo("called");
    }

    @Test
    void invalidDefaultGuildFailsStartupWhenPolicyIsActive() {
        assertThatThrownBy(() -> new McpToolPolicy(mock(JDA.class), new ObjectMapper(),
                "", "list_channels", "OPTIONAL_DEFAULT_SERVER_ID", "allow", "", "10485760"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DISCORD_GUILD_ID");
    }

    @Test
    void numericIdStillBindsThroughTheGeneratedSpringCallbackWithoutPolicy() {
        McpToolPolicy policy = policy(mock(JDA.class), "", "", "allow", "");
        ToolCallbackProvider generated = MethodToolCallbackProvider.builder()
                .toolObjects(new GeneratedSchemaTool())
                .build();

        assertThat(only(policy.apply(generated))
                .call("{\"channelId\":32345678901234567,\"message\":\"x\"}"))
                .isEqualTo("\"called\"");
    }

    @Test
    void declaredGuildParameterCanUseAllowedDefault() {
        McpToolPolicy policy = new McpToolPolicy(mock(JDA.class), new ObjectMapper(),
                ALLOWED_GUILD, "list_channels", ALLOWED_GUILD, "allow", "", "10485760");

        assertThat(only(policy.apply(provider("list_channels", new AtomicInteger()))).call("{}"))
                .isEqualTo("called");
    }

    @Test
    void absentArgumentMapsAreNormalizedToEmptyObjects() {
        AtomicInteger calls = new AtomicInteger();
        McpToolPolicy policy = new McpToolPolicy(mock(JDA.class), new ObjectMapper(),
                ALLOWED_GUILD, "list_channels", ALLOWED_GUILD, "allow", "", "10485760");
        ToolCallback callback = only(policy.apply(provider("list_channels", calls)));

        assertThat(callback.call(null)).isEqualTo("called");
        assertThat(callback.call("")).isEqualTo("called");
        assertThat(callback.call("null")).isEqualTo("called");
        assertThat(calls).hasValue(3);
    }

    @Test
    void absentArgumentsReachTheGeneratedSpringCallbackAsAnEmptyObject() {
        McpToolPolicy policy = policy(mock(JDA.class), "", "", "allow", "");
        ToolCallbackProvider generated = MethodToolCallbackProvider.builder()
                .toolObjects(new GeneratedOptionalTool())
                .build();

        assertThat(only(policy.apply(generated)).call(null)).isEqualTo("\"empty\"");
    }

    @Test
    void suppliedButUnknownChannelFailsClosed() {
        McpToolPolicy policy = policy(mock(JDA.class), ALLOWED_GUILD, "read_messages", "allow", "");

        assertThatThrownBy(() -> only(policy.apply(provider("read_messages", new AtomicInteger())))
                .call("{\"channelId\":\"32345678901234567\"}"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("channelId is not cached");
    }

    @Test
    void malformedChannelIdIsAuditedAsAnInvalidTarget() throws Exception {
        Path audit = tempDir.resolve("audit.jsonl");
        JDA jda = mock(JDA.class);
        when(jda.getGuildChannelById("not-a-snowflake"))
                .thenThrow(new NumberFormatException("malformed snowflake"));
        McpToolPolicy policy = policy(jda, ALLOWED_GUILD, "read_messages", "allow",
                audit.toString());

        assertThatThrownBy(() -> only(policy.apply(provider("read_messages", new AtomicInteger())))
                .call("{\"channelId\":\"not-a-snowflake\"}"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("channelId is not cached");

        assertThat(Files.readString(audit))
                .contains("\"outcome\":\"denied-invalid-target\"")
                .contains("\"channelId\":\"not-a-snowflake\"")
                .doesNotContain("malformed snowflake");
    }

    @Test
    void explicitAllowedGuildDoesNotExcuseAnUncachedChannelTarget() {
        McpToolPolicy policy = policy(mock(JDA.class), ALLOWED_GUILD, "edit_text_channel", "allow", "");

        assertThatThrownBy(() -> only(policy.apply(provider("edit_text_channel", new AtomicInteger())))
                .call("{\"guildId\":\"" + ALLOWED_GUILD
                        + "\",\"channelId\":\"32345678901234567\"}"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("every supplied channel target to resolve");
    }

    @Test
    void futureChannelLikeArgumentIsResolvedWithoutUpdatingAFieldList() {
        McpToolPolicy policy = policy(jdaWithChannel(DENIED_GUILD), ALLOWED_GUILD,
                "future_channel_tool", "allow", "");

        assertThatThrownBy(() -> only(policy.apply(provider("future_channel_tool", new AtomicInteger())))
                .call("{\"targetChannelId\":\"32345678901234567\"}"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("not in DISCORD_MCP_ALLOWED_GUILDS");
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
                .call("{\"channelId\":\"32345678901234567\"}"))
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
    void unknownConfiguredToolFailsStartup() {
        McpToolPolicy policy = policy(mock(JDA.class), "", "not_a_real_tool", "allow", "");

        assertThatThrownBy(() -> policy.apply(provider("read_messages", new AtomicInteger())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown tools");
    }

    @Test
    void auditRecordsIdentifiersAndHashButNotMessageBody() throws Exception {
        Path audit = tempDir.resolve("audit.jsonl");
        McpToolPolicy policy = policy(jdaWithChannel(), ALLOWED_GUILD, "send_message", "preview",
                audit.toString());

        only(policy.apply(provider("send_message", new AtomicInteger())))
                .call("{\"channelId\":\"32345678901234567\",\"message\":\"secret copy\"}");

        String line = Files.readString(audit);
        assertThat(line).contains("\"tool\":\"send_message\"")
                .contains("\"channelId\":\"32345678901234567\"")
                .contains("argumentsSha256")
                .doesNotContain("secret copy");
    }

    @Test
    void auditDoesNotRecordInviteCredentials() throws Exception {
        Path audit = tempDir.resolve("audit.jsonl");
        McpToolPolicy policy = policy(mock(JDA.class), "", "get_invite_details", "allow",
                audit.toString());

        only(policy.apply(provider("get_invite_details", new AtomicInteger())))
                .call("{\"inviteCode\":\"https://discord.gg/still-secret\"}");

        assertThat(Files.readString(audit))
                .contains("argumentsSha256")
                .doesNotContain("still-secret")
                .doesNotContain("inviteCode");
    }

    @Test
    void deniedGuildReasonSurvivesARuntimeAuditFailure() throws Exception {
        Path audit = tempDir.resolve("audit.jsonl");
        McpToolPolicy policy = policy(jdaWithChannel(DENIED_GUILD), ALLOWED_GUILD,
                "read_messages", "allow", audit.toString());
        Files.createDirectory(audit);

        assertThatThrownBy(() -> only(policy.apply(provider("read_messages", new AtomicInteger())))
                .call("{\"channelId\":\"32345678901234567\"}"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("not in DISCORD_MCP_ALLOWED_GUILDS");
    }

    @Test
    void undeclaredArgumentDenialIsAuditedWithoutSuppliedValues() throws Exception {
        Path audit = tempDir.resolve("audit.jsonl");
        McpToolPolicy policy = new McpToolPolicy(mock(JDA.class), new ObjectMapper(),
                ALLOWED_GUILD, "send_webhook_message", "", "allow", audit.toString(), "10485760");

        assertThatThrownBy(() -> only(policy.apply(provider("send_webhook_message", new AtomicInteger())))
                .call("{\"webhookUrl\":\"https://discord.com/api/webhooks/1/secret\","
                        + "\"guildId\":\"" + ALLOWED_GUILD + "\"}"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("undeclared arguments");

        assertThat(Files.readString(audit))
                .contains("\"outcome\":\"denied-undeclared-argument\"")
                .doesNotContain("webhookUrl")
                .doesNotContain("\"guildId\":")
                .doesNotContain(ALLOWED_GUILD)
                .doesNotContain("secret");
    }

    @Test
    void previewRedactsAnyLargeTextArgumentByValueSize() {
        String image = "A".repeat(8_192);
        McpToolPolicy policy = policy(mock(JDA.class), "", "create_emoji", "preview", "");

        String result = only(policy.apply(provider("create_emoji", new AtomicInteger())))
                .call("{\"image\":\"" + image + "\"}");

        assertThat(result)
                .contains("<omitted 8192 characters; sha256=")
                .doesNotContain(image);
    }

    @Test
    void returnedToolIsNotAuditedAsConfirmedDiscordExecution() throws Exception {
        Path audit = tempDir.resolve("audit.jsonl");
        McpToolPolicy policy = policy(jdaWithChannel(), ALLOWED_GUILD, "read_messages", "allow",
                audit.toString());

        only(policy.apply(provider("read_messages", new AtomicInteger())))
                .call("{\"channelId\":\"32345678901234567\"}");

        assertThat(Files.readString(audit))
                .contains("\"outcome\":\"started\"")
                .contains("\"outcome\":\"tool-returned\"")
                .doesNotContain("\"outcome\":\"executed\"");
    }

    @Test
    void whitespaceAroundAuditPathIsIgnored() {
        Path audit = tempDir.resolve("trimmed-audit.jsonl");
        McpToolPolicy policy = policy(jdaWithChannel(), ALLOWED_GUILD, "send_message", "preview",
                "  " + audit + "  ");

        only(policy.apply(provider("send_message", new AtomicInteger())))
                .call("{\"channelId\":\"32345678901234567\",\"message\":\"x\"}");

        assertThat(audit).exists();
    }

    @Test
    void oversizedIdentifierIsBoundedInAudit() throws Exception {
        Path audit = tempDir.resolve("audit.jsonl");
        McpToolPolicy policy = new McpToolPolicy(mock(JDA.class), new ObjectMapper(),
                "", "send_message", "", "preview", audit.toString(), "1024");
        String oversizedId = "1".repeat(2048);

        only(policy.apply(provider("send_message", new AtomicInteger())))
                .call("{\"channelId\":\"" + oversizedId + "\",\"message\":\"x\"}");

        assertThat(Files.readString(audit))
                .contains("<omitted 2048 characters; sha256=")
                .doesNotContain(oversizedId);
    }

    @Test
    void blankWriteModePreservesLegacyAllowBehavior() {
        AtomicInteger calls = new AtomicInteger();
        McpToolPolicy policy = policy(mock(JDA.class), "", "send_message", "", "");

        assertThat(only(policy.apply(provider("send_message", calls))).call("{}"))
                .isEqualTo("called");
        assertThat(calls).hasValue(1);
    }

    @Test
    void blankAuditMaximumUsesTheDefault() throws Exception {
        Path audit = tempDir.resolve("blank-max-audit.jsonl");
        McpToolPolicy policy = new McpToolPolicy(mock(JDA.class), new ObjectMapper(),
                "", "send_message", "", "preview", audit.toString(), "");

        only(policy.apply(provider("send_message", new AtomicInteger()))).call("{}");

        assertThat(audit).exists();
    }

    @Test
    void auditRotatesBeforeTheActiveFileWouldExceedTheCap() throws Exception {
        Path audit = tempDir.resolve("audit.jsonl");
        McpToolPolicy policy = new McpToolPolicy(jdaWithChannel(), new ObjectMapper(),
                ALLOWED_GUILD, "send_message", "", "preview", audit.toString(), "1024");
        ToolCallback callback = only(policy.apply(provider("send_message", new AtomicInteger())));

        for (int index = 0; index < 12; index++) {
            callback.call("{\"channelId\":\"32345678901234567\",\"message\":\"x" + index + "\"}");
        }

        Path rotated = audit.resolveSibling("audit.jsonl.1");
        assertThat(audit).exists();
        assertThat(rotated).exists();
        assertThat(Files.size(audit)).isLessThanOrEqualTo(1024);
        assertThat(Files.size(rotated)).isLessThanOrEqualTo(1024);
    }

    @Test
    void completedCallIsNotReportedAsFailedWhenCompletionAuditFails() throws Exception {
        Path audit = tempDir.resolve("audit.jsonl");
        McpToolPolicy policy = policy(jdaWithChannel(), ALLOWED_GUILD, "send_message", "allow",
                audit.toString());
        ToolDefinition definition = ToolDefinition.builder().name("send_message")
                .description("test").inputSchema(schema("channelId", "message")).build();
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
                .call("{\"channelId\":\"32345678901234567\",\"message\":\"x\"}");

        assertThat(result).startsWith("posted")
                .contains("audit completion record failed");
    }

    @Test
    void everyReadOnlyClassificationNamesAnExistingTool() {
        Set<String> actualTools = DiscordMcpConfig.toolServiceTypes().stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .map(method -> method.getAnnotation(Tool.class))
                .filter(annotation -> annotation != null)
                .map(Tool::name)
                .collect(Collectors.toSet());

        assertThat(McpToolPolicy.readOnlyToolNames())
                .doesNotContainAnyElementsOf(McpToolPolicy.writeToolNames());
        assertThat(actualTools).containsExactlyInAnyOrderElementsOf(
                java.util.stream.Stream.concat(McpToolPolicy.readOnlyToolNames().stream(),
                                McpToolPolicy.writeToolNames().stream())
                        .collect(Collectors.toSet()));
    }

    @Test
    void everyIdShapedToolParameterHasAReviewedGuildTargetClassification() {
        Set<String> reviewedNonChannelTargets = Set.of(
                "attachmentId", "emojiId", "eventId", "guildId", "messageId", "roleId",
                "tagIds", "targetId", "userId", "webhookId");
        Set<String> idShapedParameters = DiscordMcpConfig.toolServiceTypes().stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .filter(method -> method.getAnnotation(Tool.class) != null)
                .flatMap(method -> Arrays.stream(method.getParameters()))
                .map(parameter -> parameter.getName())
                .filter(name -> {
                    String normalized = name.toLowerCase(Locale.ROOT);
                    return normalized.endsWith("id") || normalized.endsWith("ids");
                })
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> unclassified = idShapedParameters.stream()
                .filter(name -> !McpToolPolicy.isGuildChannelArgument(name))
                .filter(name -> !reviewedNonChannelTargets.contains(name))
                .collect(Collectors.toSet());
        Set<String> staleReviews = new LinkedHashSet<>(reviewedNonChannelTargets);
        staleReviews.removeAll(idShapedParameters);

        assertThat(unclassified).as("new ID-shaped parameters need a guild-target review").isEmpty();
        assertThat(staleReviews).as("reviewed non-channel target names must still exist").isEmpty();
        assertThat(idShapedParameters).anyMatch(McpToolPolicy::isGuildChannelArgument);
    }

    private static McpToolPolicy policy(JDA jda, String guilds, String tools, String mode, String audit) {
        return new McpToolPolicy(jda, new ObjectMapper(), guilds, tools, "", mode, audit, "10485760");
    }

    private static JDA jdaWithChannel() {
        return jdaWithChannel(ALLOWED_GUILD);
    }

    private static JDA jdaWithChannel(String guildId) {
        JDA jda = mock(JDA.class);
        GuildChannel channel = mock(GuildChannel.class);
        Guild guild = mock(Guild.class);
        when(jda.getGuildChannelById("32345678901234567")).thenReturn(channel);
        when(channel.getGuild()).thenReturn(guild);
        when(guild.getId()).thenReturn(guildId);
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
                .inputSchema(switch (name) {
                    case "send_message" -> schema("channelId", "message");
                    case "read_messages" -> schema("channelId");
                    case "edit_text_channel" -> schema("guildId", "channelId");
                    case "send_webhook_message" -> schema("webhookUrl", "message");
                    case "get_invite_details" -> schema("inviteCode");
                    case "create_emoji" -> schema("image");
                    case "future_channel_tool" -> schema("targetChannelId");
                    case "list_channels" -> schema("guildId");
                    default -> schema();
                })
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

    private static String schema(String... properties) {
        String body = Arrays.stream(properties)
                .map(name -> "\"" + name + "\":{\"type\":\"string\"}")
                .collect(Collectors.joining(","));
        return "{\"type\":\"object\",\"properties\":{" + body + "}}";
    }

    static final class GeneratedSchemaTool {
        @Tool(name = "send_message", description = "test")
        public String sendMessage(@ToolParam(description = "channel") String channelId,
                                  @ToolParam(description = "message") String message) {
            return "called";
        }
    }

    static final class GeneratedOptionalTool {
        @Tool(name = "list_channels", description = "test")
        public String listChannels(
                @ToolParam(description = "guild", required = false) String guildId) {
            return guildId == null ? "empty" : guildId;
        }
    }
}
