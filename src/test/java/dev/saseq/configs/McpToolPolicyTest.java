package dev.saseq.configs;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import org.junit.jupiter.api.Assumptions;
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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
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

        assertThat(result).startsWith("WRITE_PREVIEW: This deployment runs in preview mode;")
                .contains("retrying here will produce the same result")
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
                .hasMessageContaining("unavailable or outside");
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
                .hasMessageContaining("unavailable or outside");
    }

    @Test
    void defaultGuildDoesNotMakeAnExplicitGlobalTargetToolSafe() {
        McpToolPolicy policy = new McpToolPolicy(mock(JDA.class), new ObjectMapper(),
                ALLOWED_GUILD, "send_webhook_message", ALLOWED_GUILD, "allow", "", "10485760",
                "", "", "");

        assertThatThrownBy(() -> policy.apply(provider("send_webhook_message", new AtomicInteger())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot prove guild scope");
    }

    @Test
    void explicitGlobalTargetToolFailsBeforeArgumentsCanClaimAGuild() {
        McpToolPolicy policy = policy(mock(JDA.class), ALLOWED_GUILD, "send_webhook_message", "allow", "");

        assertThatThrownBy(() -> policy.apply(provider("send_webhook_message", new AtomicInteger())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot prove guild scope");
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
    void numericChannelIdIsRejectedWhenPolicyIsActive() {
        AtomicInteger calls = new AtomicInteger();
        McpToolPolicy policy = policy(
                jdaWithChannel(), ALLOWED_GUILD, "send_message", "allow", "");

        assertThatThrownBy(() -> only(policy.apply(provider("send_message", calls)))
                .call("{\"channelId\":32345678901234567,\"message\":\"x\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channelId must be a JSON string");
        assertThat(calls).hasValue(0);
    }

    @Test
    void numericGuildIdIsRejectedWhenPolicyIsActive() {
        AtomicInteger calls = new AtomicInteger();
        McpToolPolicy policy = new McpToolPolicy(mock(JDA.class), new ObjectMapper(),
                ALLOWED_GUILD, "list_channels", ALLOWED_GUILD, "allow", "", "10485760",
                "", "", "");

        assertThatThrownBy(() -> only(policy.apply(provider("list_channels", calls)))
                .call("{\"guildId\":12345678901234567}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("guildId must be a JSON string");
        assertThat(calls).hasValue(0);
    }

    @Test
    void numericChannelIdIsRejectedForPolicyAuditWithoutAGuildAllowlist() {
        Path audit = tempDir.resolve("policy-audit.jsonl");
        AtomicInteger calls = new AtomicInteger();
        McpToolPolicy policy = policy(
                mock(JDA.class), "", "send_message", "allow", audit.toString());

        assertThatThrownBy(() -> only(policy.apply(provider("send_message", calls)))
                .call("{\"channelId\":32345678901234567,\"message\":\"x\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channelId must be a JSON string");
        assertThat(calls).hasValue(0);
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
    void unconfiguredPolicyDoesNotInspectToolSchemas() {
        ToolDefinition definition = ToolDefinition.builder().name("future_tool")
                .description("test").inputSchema("{}").build();
        ToolCallback raw = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String arguments) {
                return "called";
            }
        };
        McpToolPolicy policy = policy(mock(JDA.class), "", "", "allow", "");

        assertThat(only(policy.apply(ToolCallbackProvider.from(raw))).call("{}"))
                .isEqualTo("called");
    }

    @Test
    void policyAcceptsAnObjectSchemaWithoutPropertiesForANoArgumentTool() {
        ToolDefinition definition = ToolDefinition.builder().name("send_message")
                .description("test").inputSchema("{\"type\":\"object\"}").build();
        ToolCallback raw = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String arguments) {
                return "called";
            }
        };
        McpToolPolicy policy = policy(mock(JDA.class), "", "send_message", "preview", "");

        assertThat(only(policy.apply(ToolCallbackProvider.from(raw))).call("{}"))
                .startsWith("WRITE_PREVIEW:");
    }

    @Test
    void auditOnlyForwardsArgumentsUnchanged() {
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
        Path audit = tempDir.resolve("audit-only.jsonl");
        McpToolPolicy policy = policy(mock(JDA.class), "", "", "allow", audit.toString());
        String arguments = "{ \"message\" : \"x\", \"channelId\" : 32345678901234567 }";

        assertThat(only(policy.apply(ToolCallbackProvider.from(raw))).call(arguments))
                .isEqualTo("called");
        assertThat(received).hasValue(arguments);
        assertThat(audit).exists();
    }

    @Test
    void auditOnlyDefersArgumentParsingToTheDelegate() {
        AtomicReference<String> received = new AtomicReference<>();
        ToolDefinition definition = ToolDefinition.builder().name("send_file")
                .description("test").inputSchema(schema("channelId", "fileData")).build();
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
        Path audit = tempDir.resolve("audit-only-large.jsonl");
        McpToolPolicy policy = policy(mock(JDA.class), "", "", "allow", audit.toString());
        String arguments = "{not parsed by policy:" + "A".repeat(1_000_000);

        assertThat(only(policy.apply(ToolCallbackProvider.from(raw))).call(arguments))
                .isEqualTo("called");
        assertThat(received.get()).isSameAs(arguments);
        assertThat(audit).exists();
    }

    @Test
    void policyRejectsArgumentsAboveTheTransportCompatibleStringLimit() {
        AtomicInteger calls = new AtomicInteger();
        ToolDefinition definition = ToolDefinition.builder().name("send_file")
                .description("test").inputSchema(schema("channelId", "fileData")).build();
        ToolCallback raw = new ToolCallback() {
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
        McpToolPolicy policy = policy(jdaWithChannel(), ALLOWED_GUILD, "send_file", "allow", "");
        String arguments = "{\"channelId\":\"32345678901234567\",\"fileData\":\""
                + "A".repeat(20_000_004) + "\"}";

        assertThatThrownBy(() -> only(policy.apply(ToolCallbackProvider.from(raw))).call(arguments))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transport size limit")
                .hasMessageContaining("filePath or fileUrl");
        assertThat(calls).hasValue(0);
    }

    @Test
    void invalidLegacyDefaultGuildDoesNotBlockStartupWithoutPolicy() {
        McpToolPolicy policy = new McpToolPolicy(mock(JDA.class), new ObjectMapper(),
                "", "", "OPTIONAL_DEFAULT_SERVER_ID", "allow", "", "not-an-integer", "", "", "");

        assertThat(only(policy.apply(provider("list_channels", new AtomicInteger())))
                .call("{\"guildId\":\"123\"}"))
                .isEqualTo("called");
    }

    @Test
    void invalidDefaultGuildFailsStartupWhenPolicyIsActive() {
        assertThatThrownBy(() -> new McpToolPolicy(mock(JDA.class), new ObjectMapper(),
                "", "list_channels", "OPTIONAL_DEFAULT_SERVER_ID", "allow", "", "10485760",
                "", "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DISCORD_GUILD_ID");
    }

    @Test
    void whitespacePaddedDefaultGuildFailsStartupWhenPolicyIsActive() {
        assertThatThrownBy(() -> new McpToolPolicy(mock(JDA.class), new ObjectMapper(),
                ALLOWED_GUILD, "list_channels", " " + ALLOWED_GUILD + " ", "allow", "",
                "10485760", "", "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leading or trailing whitespace");
    }

    @Test
    void invalidGuildFailsBeforeAuditFilesystemChanges() {
        Path audit = tempDir.resolve("new-audit-directory").resolve("audit.jsonl");

        assertThatThrownBy(() -> new McpToolPolicy(mock(JDA.class), new ObjectMapper(),
                "not-a-snowflake", "", "", "allow", audit.toString(), "10485760",
                "", "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DISCORD_MCP_ALLOWED_GUILDS");
        assertThat(audit.getParent()).doesNotExist();
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
                ALLOWED_GUILD, "list_channels", ALLOWED_GUILD, "allow", "", "10485760",
                "", "", "");

        assertThat(only(policy.apply(provider("list_channels", new AtomicInteger()))).call("{}"))
                .isEqualTo("called");
    }

    @Test
    void absentArgumentMapsAreNormalizedToEmptyObjects() {
        AtomicInteger calls = new AtomicInteger();
        McpToolPolicy policy = new McpToolPolicy(mock(JDA.class), new ObjectMapper(),
                ALLOWED_GUILD, "list_channels", ALLOWED_GUILD, "allow", "", "10485760",
                "", "", "");
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
        assertThat(only(policy.apply(generated)).call(" null ")).isEqualTo("\"empty\"");
    }

    @Test
    void suppliedButUnknownChannelFailsClosed() {
        McpToolPolicy policy = policy(mock(JDA.class), ALLOWED_GUILD, "read_messages", "allow", "");

        assertThatThrownBy(() -> only(policy.apply(provider("read_messages", new AtomicInteger())))
                .call("{\"channelId\":\"32345678901234567\"}"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("unavailable or outside");
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
                .hasMessageContaining("unavailable or outside");

        assertThat(Files.readString(audit))
                .contains("\"outcome\":\"denied-invalid-target\"")
                .contains("\"channelId\":\"not-a-snowflake\"")
                .doesNotContain("malformed snowflake");
    }

    @Test
    void unexpectedJdaLookupFailureUsesTheSameAuditedTargetDenial() throws Exception {
        Path audit = tempDir.resolve("audit.jsonl");
        JDA jda = mock(JDA.class);
        when(jda.getGuildChannelById("32345678901234567"))
                .thenThrow(new IllegalStateException("internal lookup detail"));
        McpToolPolicy policy = policy(jda, ALLOWED_GUILD, "read_messages", "allow",
                audit.toString());

        assertThatThrownBy(() -> only(policy.apply(provider("read_messages", new AtomicInteger())))
                .call("{\"channelId\":\"32345678901234567\"}"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("unavailable or outside")
                .hasMessageNotContaining("internal lookup detail");

        assertThat(Files.readString(audit))
                .contains("\"outcome\":\"denied-invalid-target\"")
                .doesNotContain("internal lookup detail");
    }

    @Test
    void malformedArgumentsAreDeniedAndAuditedWithoutTheirContent() throws Exception {
        Path audit = tempDir.resolve("audit.jsonl");
        String malformed = "{\"message\":\"secret copy\"";
        McpToolPolicy policy = policy(mock(JDA.class), ALLOWED_GUILD,
                "send_message", "allow", audit.toString());

        assertThatThrownBy(() -> only(policy.apply(provider("send_message", new AtomicInteger())))
                .call(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid JSON");

        assertThat(Files.readString(audit))
                .contains("\"outcome\":\"denied-invalid-arguments\"")
                .contains("\"argumentsSaltedSha256\"")
                .contains("\"errorType\":\"IllegalArgumentException\"")
                .doesNotContain("secret copy");
    }

    @Test
    void duplicateArgumentKeysAreRejectedBeforeTheDelegateRuns() {
        AtomicInteger calls = new AtomicInteger();
        McpToolPolicy policy = policy(jdaWithChannel(), ALLOWED_GUILD,
                "read_messages", "allow", "");
        ToolCallback callback = only(policy.apply(provider("read_messages", calls)));

        assertThatThrownBy(() -> callback.call("{\"channelId\":\"32345678901234567\","
                + "\"channelId\":\"42345678901234567\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid JSON");
        assertThat(calls).hasValue(0);
    }

    @Test
    void nonObjectArgumentsAreDeniedAndAudited() throws Exception {
        Path audit = tempDir.resolve("audit.jsonl");
        McpToolPolicy policy = policy(mock(JDA.class), ALLOWED_GUILD,
                "send_message", "allow", audit.toString());

        assertThatThrownBy(() -> only(policy.apply(provider("send_message", new AtomicInteger())))
                .call("[\"not-an-object\"]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a JSON object");

        assertThat(Files.readString(audit))
                .contains("\"outcome\":\"denied-invalid-arguments\"")
                .contains("\"argumentsSaltedSha256\"")
                .doesNotContain("not-an-object");
    }

    @Test
    void malformedGuildIdCannotFlushTheAudit() throws Exception {
        Path audit = tempDir.resolve("audit.jsonl");
        String oversizedGuildId = "1".repeat(2_048);
        McpToolPolicy policy = new McpToolPolicy(mock(JDA.class), new ObjectMapper(),
                ALLOWED_GUILD, "list_channels", "", "allow", audit.toString(), "4096", "", "", "");

        assertThatThrownBy(() -> only(policy.apply(provider("list_channels", new AtomicInteger())))
                .call("{\"guildId\":\"" + oversizedGuildId + "\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("17-20 digit Discord snowflake");

        assertThat(Files.readString(audit))
                .contains("\"outcome\":\"denied-invalid-target\"")
                .contains("<omitted 2048 characters; saltedSha256=")
                .doesNotContain(oversizedGuildId);
        assertThat(Files.size(audit)).isLessThanOrEqualTo(4096);
    }

    @Test
    void explicitAllowedGuildDoesNotExcuseAnUncachedChannelTarget() {
        McpToolPolicy policy = policy(mock(JDA.class), ALLOWED_GUILD, "edit_text_channel", "allow", "");

        assertThatThrownBy(() -> only(policy.apply(provider("edit_text_channel", new AtomicInteger())))
                .call("{\"guildId\":\"" + ALLOWED_GUILD
                        + "\",\"channelId\":\"32345678901234567\"}"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("unavailable or outside");
    }

    @Test
    void futureChannelLikeArgumentIsResolvedWithoutUpdatingAFieldList() {
        McpToolPolicy policy = policy(jdaWithChannel(DENIED_GUILD), ALLOWED_GUILD,
                "future_channel_tool", "allow", "");

        assertThatThrownBy(() -> only(policy.apply(provider("future_channel_tool", new AtomicInteger())))
                .call("{\"targetChannelId\":\"32345678901234567\"}"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("unavailable or outside");
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
        McpToolPolicy policy = policy(jdaWithChannel(), ALLOWED_GUILD, "send_message", "allow",
                audit.toString());

        only(policy.apply(provider("send_message", new AtomicInteger())))
                .call("{\"channelId\":\"32345678901234567\",\"message\":\"secret copy\"}");

        var lines = Files.readAllLines(audit);
        var mapper = new ObjectMapper();
        String startedHash = mapper.readTree(lines.get(0)).get("argumentsSaltedSha256").asText();
        String terminalHash = mapper.readTree(lines.get(1)).get("argumentsSaltedSha256").asText();
        assertThat(String.join("\n", lines)).contains("\"tool\":\"send_message\"")
                .contains("\"channelId\":\"32345678901234567\"")
                .contains("argumentsSaltedSha256")
                .doesNotContain("secret copy");
        assertThat(startedHash).hasSize(64).isEqualTo(terminalHash);
    }

    @Test
    void auditDoesNotRecordInviteCredentials() throws Exception {
        Path audit = tempDir.resolve("audit.jsonl");
        McpToolPolicy policy = policy(mock(JDA.class), "", "get_invite_details", "allow",
                audit.toString());

        only(policy.apply(provider("get_invite_details", new AtomicInteger())))
                .call("{\"inviteCode\":\"https://discord.gg/still-secret\"}");

        String arguments = "{\"inviteCode\":\"https://discord.gg/still-secret\"}";
        var lines = Files.readAllLines(audit);
        String firstHash = new ObjectMapper().readTree(lines.get(0))
                .get("argumentsSaltedSha256").asText();
        Path secondAudit = tempDir.resolve("second-audit.jsonl");
        McpToolPolicy secondPolicy = policy(mock(JDA.class), "", "get_invite_details", "allow",
                secondAudit.toString());
        only(secondPolicy.apply(provider("get_invite_details", new AtomicInteger())))
                .call(arguments);
        String secondHash = new ObjectMapper().readTree(Files.readAllLines(secondAudit).get(0))
                .get("argumentsSaltedSha256").asText();

        assertThat(Files.readString(audit))
                .contains("argumentsSaltedSha256")
                .doesNotContain("still-secret")
                .doesNotContain("inviteCode");
        assertThat(firstHash).hasSize(64).isNotEqualTo(secondHash);
    }

    @Test
    void deniedGuildReasonSurvivesARuntimeAuditFailure() throws Exception {
        Path audit = tempDir.resolve("audit.jsonl");
        McpToolPolicy policy = policy(jdaWithChannel(DENIED_GUILD), ALLOWED_GUILD,
                "read_messages", "allow", audit.toString());
        Files.delete(audit);
        Files.createDirectory(audit);

        assertThatThrownBy(() -> only(policy.apply(provider("read_messages", new AtomicInteger())))
                .call("{\"channelId\":\"32345678901234567\"}"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("unavailable or outside");
    }

    @Test
    void delegateErrorsStillProduceATerminalAuditRecord() throws Exception {
        Path audit = tempDir.resolve("error-audit.jsonl");
        ToolCallback raw = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("list_channels")
                        .description("test").inputSchema(schema("guildId")).build();
            }

            @Override
            public String call(String arguments) {
                throw new AssertionError("simulated fatal delegate error");
            }
        };
        McpToolPolicy policy = policy(mock(JDA.class), ALLOWED_GUILD,
                "list_channels", "allow", audit.toString());

        assertThatThrownBy(() -> only(policy.apply(ToolCallbackProvider.from(raw)))
                .call("{\"guildId\":\"" + ALLOWED_GUILD + "\"}"))
                .isInstanceOf(AssertionError.class);
        assertThat(Files.readString(audit))
                .contains("\"outcome\":\"started\"")
                .contains("\"outcome\":\"failed\"")
                .contains("\"errorType\":\"AssertionError\"");
    }

    @Test
    void undeclaredArgumentDenialIsAuditedWithoutSuppliedValues() throws Exception {
        Path audit = tempDir.resolve("audit.jsonl");
        McpToolPolicy policy = new McpToolPolicy(jdaWithChannel(), new ObjectMapper(),
                ALLOWED_GUILD, "send_message", "", "allow", audit.toString(), "10485760",
                "", "", "");

        assertThatThrownBy(() -> only(policy.apply(provider("send_message", new AtomicInteger())))
                .call("{\"channelId\":\"32345678901234567\",\"message\":\"secret\","
                        + "\"webhookUrl\":\"https://discord.com/api/webhooks/1/secret\"}"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("undeclared arguments");

        assertThat(Files.readString(audit))
                .contains("\"outcome\":\"denied-undeclared-argument\"")
                .contains("\"argumentsSaltedSha256\":")
                .doesNotContain("webhookUrl")
                .doesNotContain("\"guildId\":")
                .doesNotContain(ALLOWED_GUILD)
                .doesNotContain("secret");
    }

    @Test
    void undeclaredArgumentErrorBoundsCallerControlledKeyNames() {
        McpToolPolicy policy = policy(jdaWithChannel(), ALLOWED_GUILD,
                "send_message", "allow", "");
        var arguments = new ObjectMapper().createObjectNode();
        arguments.put("channelId", "32345678901234567");
        arguments.put("message", "x");
        String oversizedKey = "secret-" + "k".repeat(500);
        for (int index = 0; index < 20; index++) {
            arguments.put(oversizedKey + index, "x");
        }

        Throwable denial = catchThrowable(() -> only(policy.apply(
                provider("send_message", new AtomicInteger()))).call(arguments.toString()));

        assertThat(denial).isInstanceOf(SecurityException.class);
        assertThat(denial.getMessage())
                .contains("received 20 undeclared arguments")
                .contains("additional keys omitted")
                .doesNotContain(oversizedKey);
        assertThat(denial.getMessage().length()).isLessThan(500);
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
    void previewSummarizesNestedValuesBeforeSerialization() {
        String nestedItems = "\"x\",".repeat(50_000) + "\"x\"";
        McpToolPolicy policy = policy(mock(JDA.class), "", "create_emoji", "preview", "");

        String result = only(policy.apply(provider("create_emoji", new AtomicInteger())))
                .call("{\"image\":[" + nestedItems + "]}");

        assertThat(result)
                .contains("<omitted array with 50001 top-level entries>")
                .hasSizeLessThan(16_500)
                .doesNotContain(nestedItems);
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
    void auditPairsStartedAndTerminalRecordsByUniqueInvocation() throws Exception {
        Path audit = tempDir.resolve("audit.jsonl");
        McpToolPolicy policy = policy(jdaWithChannel(), ALLOWED_GUILD, "read_messages", "allow",
                audit.toString());
        ToolCallback callback = only(policy.apply(provider("read_messages", new AtomicInteger())));

        callback.call("{\"channelId\":\"32345678901234567\"}");
        callback.call("{\"channelId\":\"32345678901234567\"}");

        var lines = Files.readAllLines(audit);
        var mapper = new ObjectMapper();
        String firstStarted = mapper.readTree(lines.get(0)).get("invocationId").asText();
        String firstReturned = mapper.readTree(lines.get(1)).get("invocationId").asText();
        String secondStarted = mapper.readTree(lines.get(2)).get("invocationId").asText();
        String secondReturned = mapper.readTree(lines.get(3)).get("invocationId").asText();

        assertThat(firstStarted).isNotBlank().isEqualTo(firstReturned);
        assertThat(secondStarted).isNotBlank().isEqualTo(secondReturned).isNotEqualTo(firstStarted);
    }

    @Test
    void auditOnlyArgumentsCannotOverwriteReservedFields() throws Exception {
        Path audit = tempDir.resolve("audit.jsonl");
        McpToolPolicy policy = policy(mock(JDA.class), "", "", "allow", audit.toString());

        only(policy.apply(provider("read_messages", new AtomicInteger())))
                .call("{\"invocationId\":\"spoofed\",\"inventedId\":\"123\"}");

        var lines = Files.readAllLines(audit);
        var mapper = new ObjectMapper();
        var started = mapper.readTree(lines.get(0));
        var returned = mapper.readTree(lines.get(1));
        assertThat(started.get("invocationId").asText())
                .isNotEqualTo("spoofed")
                .isEqualTo(returned.get("invocationId").asText());
        assertThat(started.has("argumentIds")).isFalse();
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
                "", "send_message", "", "preview", audit.toString(), "4096", "", "", "");
        String oversizedId = "1".repeat(2048);

        only(policy.apply(provider("send_message", new AtomicInteger())))
                .call("{\"channelId\":\"" + oversizedId + "\",\"message\":\"x\"}");

        String firstAudit = Files.readString(audit);
        Path secondAudit = tempDir.resolve("second-audit.jsonl");
        McpToolPolicy secondPolicy = new McpToolPolicy(mock(JDA.class), new ObjectMapper(),
                "", "send_message", "", "preview", secondAudit.toString(), "4096", "", "", "");
        only(secondPolicy.apply(provider("send_message", new AtomicInteger())))
                .call("{\"channelId\":\"" + oversizedId + "\",\"message\":\"x\"}");

        String secondAuditContent = Files.readString(secondAudit);
        assertThat(firstAudit)
                .contains("<omitted 2048 characters; saltedSha256=")
                .doesNotContain(oversizedId);
        assertThat(firstAudit).isNotEqualTo(secondAuditContent);
    }

    @Test
    void auditFileMustRemainOutsideUploadAndDownloadRoots() throws Exception {
        Path uploads = Files.createDirectories(tempDir.resolve("uploads"));
        Path downloads = Files.createDirectories(tempDir.resolve("downloads"));

        assertThatThrownBy(() -> new McpToolPolicy(mock(JDA.class), new ObjectMapper(),
                "", "send_message", "", "preview", uploads.resolve("audit.jsonl").toString(),
                "10485760", uploads.toString(), "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside DISCORD_MCP_FILE_ROOT");
        assertThat(uploads.resolve("audit.jsonl")).doesNotExist();
        assertThatThrownBy(() -> new McpToolPolicy(mock(JDA.class), new ObjectMapper(),
                "", "send_message", "", "preview", downloads.resolve("audit.jsonl").toString(),
                "10485760", "", downloads.toString(), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside DISCORD_MCP_DOWNLOAD_ROOT");
        assertThat(downloads.resolve("audit.jsonl")).doesNotExist();
    }

    @Test
    void resolvedAuditIsolationRunsBeforeCreatingTheAuditFile() throws Exception {
        Path realRoot = Files.createDirectories(tempDir.resolve("real-uploads"));
        Path aliasRoot = tempDir.resolve("uploads-alias");
        try {
            Files.createSymbolicLink(aliasRoot, realRoot);
        } catch (UnsupportedOperationException | SecurityException | java.io.IOException error) {
            Assumptions.abort("directory symbolic links are unavailable on this filesystem");
        }
        Path audit = aliasRoot.resolve("new-parent").resolve("audit.jsonl");

        assertThatThrownBy(() -> new McpToolPolicy(mock(JDA.class), new ObjectMapper(),
                "", "send_message", "", "preview", audit.toString(), "10485760",
                realRoot.toString(), "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside DISCORD_MCP_FILE_ROOT");
        assertThat(realRoot.resolve("new-parent")).doesNotExist();
    }

    @Test
    void lexicalAuditIsolationStillAppliesWhenConfiguredRootIsUnusable() {
        Path audit = tempDir.resolve("audit.jsonl");

        assertThatThrownBy(() -> new McpToolPolicy(mock(JDA.class), new ObjectMapper(),
                "", "send_message", "", "preview", audit.toString(),
                "10485760", tempDir.getRoot().toString(), "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside DISCORD_MCP_FILE_ROOT");
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
                "", "send_message", "", "preview", audit.toString(), "", "", "", "");

        only(policy.apply(provider("send_message", new AtomicInteger()))).call("{}");

        assertThat(audit).exists();
    }

    @Test
    void auditMaximumRequiresEnoughRoomForACompleteRecord() {
        Path audit = tempDir.resolve("small-audit.jsonl");

        assertThatThrownBy(() -> new McpToolPolicy(mock(JDA.class), new ObjectMapper(),
                "", "send_message", "", "preview", audit.toString(), "4095", "", "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 4096");
    }

    @Test
    void auditRotatesBeforeTheActiveFileWouldExceedTheCap() throws Exception {
        Path audit = tempDir.resolve("audit.jsonl");
        McpToolPolicy policy = new McpToolPolicy(jdaWithChannel(), new ObjectMapper(),
                ALLOWED_GUILD, "send_message", "", "preview", audit.toString(), "4096", "", "", "");
        ToolCallback callback = only(policy.apply(provider("send_message", new AtomicInteger())));

        for (int index = 0; index < 40; index++) {
            callback.call("{\"channelId\":\"32345678901234567\",\"message\":\"x" + index + "\"}");
        }

        Path rotated = audit.resolveSibling("audit.jsonl.1");
        assertThat(audit).exists();
        assertThat(rotated).exists();
        assertThat(Files.size(audit)).isLessThanOrEqualTo(4096);
        assertThat(Files.size(rotated)).isLessThanOrEqualTo(4096);
        if (audit.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(Files.getPosixFilePermissions(audit)).containsExactlyInAnyOrder(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
        }
    }

    @Test
    void auditDiscardsPreExistingFilesThatExceedALoweredCap() throws Exception {
        Path audit = tempDir.resolve("audit.jsonl");
        Path rotated = audit.resolveSibling("audit.jsonl.1");
        Files.writeString(audit, "A".repeat(5_000));
        Files.writeString(rotated, "B".repeat(5_000));
        McpToolPolicy policy = new McpToolPolicy(jdaWithChannel(), new ObjectMapper(),
                ALLOWED_GUILD, "send_message", "", "preview", audit.toString(), "4096", "", "", "");

        only(policy.apply(provider("send_message", new AtomicInteger())))
                .call("{\"channelId\":\"32345678901234567\",\"message\":\"x\"}");

        assertThat(audit).exists();
        assertThat(Files.size(audit)).isLessThanOrEqualTo(4096);
        assertThat(rotated).doesNotExist();
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
    void readOnlyCallFailsClosedWhenStartAuditFails() throws Exception {
        Path audit = tempDir.resolve("audit.jsonl");
        AtomicInteger calls = new AtomicInteger();
        McpToolPolicy policy = policy(jdaWithChannel(), ALLOWED_GUILD, "read_messages", "allow",
                audit.toString());
        Files.delete(audit);
        Files.createDirectory(audit);

        assertThatThrownBy(() -> only(policy.apply(provider("read_messages", calls)))
                .call("{\"channelId\":\"32345678901234567\"}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not append");
        assertThat(calls).hasValue(0);
    }

    @Test
    void auditPathMustBeARegularFileAtStartup() throws Exception {
        Path audit = tempDir.resolve("audit.jsonl");
        Files.createDirectory(audit);

        assertThatThrownBy(() -> policy(jdaWithChannel(), ALLOWED_GUILD,
                "read_messages", "allow", audit.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("regular file");
    }

    @Test
    void auditRotationPathMustBeARegularFileAtStartup() throws Exception {
        Path audit = tempDir.resolve("audit.jsonl");
        Files.createDirectory(tempDir.resolve("audit.jsonl.1"));

        assertThatThrownBy(() -> policy(jdaWithChannel(), ALLOWED_GUILD,
                "read_messages", "allow", audit.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rotation target")
                .hasMessageContaining("regular file");
    }

    @Test
    void auditPathMustNotHaveAdditionalHardLinksWhenLinkCountsAreAvailable() throws Exception {
        Assumptions.assumeTrue(
                tempDir.getFileSystem().supportedFileAttributeViews().contains("unix"),
                "unix:nlink is unavailable on this filesystem");
        Path uploads = Files.createDirectories(tempDir.resolve("uploads"));
        Path audit = tempDir.resolve("audit.jsonl");
        Files.writeString(audit, "existing evidence");
        try {
            Files.createLink(uploads.resolve("audit-alias.jsonl"), audit);
        } catch (UnsupportedOperationException | SecurityException | java.io.IOException error) {
            Assumptions.abort("hard links are unavailable on this filesystem");
        }

        assertThatThrownBy(() -> new McpToolPolicy(mock(JDA.class), new ObjectMapper(),
                "", "send_message", "", "preview", audit.toString(),
                "10485760", uploads.toString(), "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("additional hard links");
    }

    @Test
    void auditRotationPathMustNotHaveAdditionalHardLinksWhenLinkCountsAreAvailable() throws Exception {
        Assumptions.assumeTrue(
                tempDir.getFileSystem().supportedFileAttributeViews().contains("unix"),
                "unix:nlink is unavailable on this filesystem");
        Path audit = tempDir.resolve("audit.jsonl");
        Path rotated = tempDir.resolve("audit.jsonl.1");
        Files.writeString(rotated, "existing rotated evidence");
        try {
            Files.createLink(tempDir.resolve("audit-rotation-alias.jsonl"), rotated);
        } catch (UnsupportedOperationException | SecurityException | java.io.IOException error) {
            Assumptions.abort("hard links are unavailable on this filesystem");
        }

        assertThatThrownBy(() -> policy(jdaWithChannel(), ALLOWED_GUILD,
                "read_messages", "allow", audit.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rotation target");
    }

    @Test
    void auditPathMustNotBeASymbolicLink() throws Exception {
        Path target = tempDir.resolve("audit-target.jsonl");
        Path audit = tempDir.resolve("audit-link.jsonl");
        Files.writeString(target, "existing evidence");
        boolean symlinkCreated = true;
        try {
            Files.createSymbolicLink(audit, target);
        } catch (UnsupportedOperationException | SecurityException | java.io.IOException error) {
            symlinkCreated = false;
        }
        Assumptions.assumeTrue(symlinkCreated, "symbolic links are unavailable on this filesystem");

        assertThatThrownBy(() -> policy(jdaWithChannel(), ALLOWED_GUILD,
                "read_messages", "allow", audit.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be a symbolic link");
        assertThat(Files.readString(target)).isEqualTo("existing evidence");
    }

    @Test
    void invalidAuditPathNamesItsOwningSetting() {
        assertThatThrownBy(() -> policy(jdaWithChannel(), ALLOWED_GUILD,
                "read_messages", "allow", "invalid\u0000audit"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DISCORD_MCP_AUDIT_FILE is not a valid path");
    }

    @Test
    void auditFileUsesOwnerOnlyPermissionsOnPosixFilesystems() throws Exception {
        Path audit = tempDir.resolve("private-audit.jsonl");

        policy(jdaWithChannel(), ALLOWED_GUILD,
                "read_messages", "allow", audit.toString());

        if (audit.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(Files.getPosixFilePermissions(audit)).containsExactlyInAnyOrder(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
        }
    }

    @Test
    void auditAppendPreservesOperatorGrantedPosixGroupRead() throws Exception {
        Path audit = tempDir.resolve("collector-readable-audit.jsonl");
        Assumptions.assumeTrue(
                audit.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions are unavailable on this filesystem");
        McpToolPolicy policy = policy(jdaWithChannel(), ALLOWED_GUILD,
                "read_messages", "allow", audit.toString());
        Files.setPosixFilePermissions(audit, Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.GROUP_READ));

        only(policy.apply(provider("read_messages", new AtomicInteger())))
                .call("{\"channelId\":\"32345678901234567\"}");

        assertThat(Files.getPosixFilePermissions(audit)).containsExactlyInAnyOrder(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.GROUP_READ);
    }

    @Test
    void auditAndRotationFilesMustDifferFromTheOperationalLog() throws Exception {
        Path audit = tempDir.resolve("audit.jsonl");
        assertThatThrownBy(() -> policy(jdaWithChannel(), ALLOWED_GUILD,
                "read_messages", "allow", audit.toString(), audit.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ from logging.file.name");
        assertThat(audit).doesNotExist();

        Path secondAudit = tempDir.resolve("second-audit.jsonl");
        Path rotated = secondAudit.resolveSibling(secondAudit.getFileName() + ".1");
        assertThatThrownBy(() -> policy(jdaWithChannel(), ALLOWED_GUILD,
                "read_messages", "allow", secondAudit.toString(), rotated.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ from logging.file.name");
        assertThat(secondAudit).doesNotExist();
    }

    @Test
    void auditParentCreationFailureHasAnActionableStartupError() throws Exception {
        Path blockingFile = tempDir.resolve("not-a-directory");
        Files.writeString(blockingFile, "x");
        Path audit = blockingFile.resolve("audit.jsonl");

        assertThatThrownBy(() -> policy(jdaWithChannel(), ALLOWED_GUILD,
                "read_messages", "allow", audit.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parent cannot be created");
    }

    @Test
    void everyReadOnlyClassificationNamesAnExistingTool() {
        Set<String> actualTools = DiscordMcpConfig.toolServiceTypes().stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .map(method -> method.getAnnotation(Tool.class))
                .filter(annotation -> annotation != null)
                .map(Tool::name)
                .collect(Collectors.toSet());

        assertThat(actualTools).containsAll(McpToolPolicy.readOnlyToolNames());
    }

    @Test
    void exportedToolInventoryRequiresAnExplicitReviewWhenItChanges() {
        Set<String> actualTools = DiscordMcpConfig.toolServiceTypes().stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .map(method -> method.getAnnotation(Tool.class))
                .filter(annotation -> annotation != null)
                .map(Tool::name)
                .collect(Collectors.toSet());
        Set<String> reviewedTools = Set.of(
                "add_reaction", "assign_role", "ban_member", "create_category", "create_emoji",
                "create_forum_channel", "create_forum_post", "create_guild_scheduled_event",
                "create_invite", "create_role", "create_stage_channel", "create_text_channel",
                "create_voice_channel", "create_webhook", "delete_category", "delete_channel",
                "delete_channel_permission_overwrite", "delete_emoji",
                "delete_guild_scheduled_event", "delete_invite", "delete_message",
                "delete_private_message", "delete_role", "delete_webhook", "disconnect_member",
                "download_attachment", "edit_category", "edit_emoji", "edit_forum_channel",
                "edit_guild_scheduled_event", "edit_message", "edit_private_message", "edit_role",
                "edit_text_channel", "edit_voice_channel", "find_category", "find_channel",
                "get_attachment", "get_bans", "get_channel_info", "get_emoji_details",
                "get_forum_channel_info", "get_guild_scheduled_event_users", "get_invite_details",
                "get_member_by_id", "get_server_info", "get_user_id_by_name", "kick_member",
                "list_active_threads", "list_channel_permission_overwrites", "list_channels",
                "list_channels_in_category", "list_emojis", "list_forum_channels",
                "list_forum_posts", "list_forum_tags", "list_guild_scheduled_events",
                "list_invites", "list_roles", "list_webhooks", "modify_forum_post",
                "modify_voice_state", "move_channel", "move_member", "read_messages",
                "read_private_messages", "remove_reaction", "remove_role", "remove_timeout",
                "search_members", "send_file", "send_message", "send_private_message",
                "send_webhook_message", "set_guild_scheduled_event_image", "set_nickname",
                "timeout_member", "unban_member", "upsert_member_channel_permissions",
                "upsert_role_channel_permissions");

        assertThat(actualTools).containsExactlyInAnyOrderElementsOf(reviewedTools);
    }

    @Test
    void everyToolParameterHasAReviewedGuildTargetClassification() {
        Set<String> reviewedChannelParameters = Set.of("categoryId", "channelId", "postId");
        Set<String> reviewedNonChannelParameters = Set.of(
                "after", "allowPermissions", "allowRaw", "archived", "around", "attachmentId",
                "before", "bitrate", "categoryName", "channelName", "color", "count",
                "deafen", "defaultLayout", "defaultSort", "deleteMessageSeconds", "denyPermissions",
                "denyRaw", "description", "durationSeconds", "emoji", "emojiId", "entityType",
                "eventId", "fileData", "fileName", "filePath", "fileUrl", "guildId", "hoist",
                "image", "imageUrl", "inviteCode", "limit", "location", "locked", "maxAge",
                "maxUses", "mentionable", "message", "messageId", "mute", "name", "newMessage",
                "nick", "nsfw", "permissions", "pinned", "position", "query", "reason",
                "recurrenceRule", "roleId", "roles", "rtcRegion", "scheduledEndTime",
                "scheduledStartTime", "slowmode", "status", "tagIds", "targetId", "targetType",
                "temporary", "title", "topic", "unique", "userId", "userLimit", "username",
                "webhookId", "webhookUrl", "withCounts", "withMember", "withUserCount");
        Set<String> toolParameters = DiscordMcpConfig.toolServiceTypes().stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .filter(method -> method.getAnnotation(Tool.class) != null)
                .flatMap(method -> Arrays.stream(method.getParameters()))
                .map(parameter -> parameter.getName())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> unclassified = toolParameters.stream()
                .filter(name -> !McpToolPolicy.isGuildChannelArgument(name))
                .filter(name -> !reviewedNonChannelParameters.contains(name))
                .collect(Collectors.toSet());
        Set<String> staleReviews = new LinkedHashSet<>(reviewedNonChannelParameters);
        staleReviews.removeAll(toolParameters);
        Set<String> channelParameters = toolParameters.stream()
                .filter(McpToolPolicy::isGuildChannelArgument)
                .collect(Collectors.toSet());

        assertThat(unclassified).as("new tool parameters need an explicit guild-target review").isEmpty();
        assertThat(staleReviews).as("reviewed non-channel parameter names must still exist").isEmpty();
        assertThat(channelParameters)
                .as("channel-classified parameter names need an explicit guild-target review")
                .containsExactlyInAnyOrderElementsOf(reviewedChannelParameters);
    }

    @Test
    void everyToolHasOneUnambiguousGuildTargetStrategy() {
        Set<String> globalTargetTools = McpToolPolicy.globalTargetToolNames();
        Set<String> globalIdentifierParameters = Set.of("webhookUrl", "webhookId", "inviteCode");
        Map<String, Set<String>> parametersByTool = DiscordMcpConfig.toolServiceTypes().stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .filter(method -> method.getAnnotation(Tool.class) != null)
                .collect(Collectors.toMap(
                        method -> method.getAnnotation(Tool.class).name(),
                        method -> Arrays.stream(method.getParameters())
                                .map(parameter -> parameter.getName())
                                .collect(Collectors.toSet())));

        parametersByTool.forEach((tool, parameters) -> {
            boolean hasGuildTarget = parameters.contains("guildId")
                    || parameters.stream().anyMatch(McpToolPolicy::isGuildChannelArgument);
            boolean hasGlobalIdentifier = parameters.stream().anyMatch(globalIdentifierParameters::contains);
            if (globalTargetTools.contains(tool)) {
                assertThat(hasGuildTarget)
                        .as(tool + " is global-target classified and cannot borrow guild authorization")
                        .isFalse();
            } else {
                assertThat(hasGuildTarget)
                        .as(tool + " must declare a guild-resolvable target")
                        .isTrue();
            }
            if (hasGlobalIdentifier) {
                assertThat(globalTargetTools)
                        .as(tool + " has a global identifier and cannot borrow guild authorization")
                        .contains(tool);
            }
        });
    }

    @Test
    void policyWrapsTheGeneratedSchemasForEveryRealToolService() {
        JDA jda = mock(JDA.class);
        Object[] toolServices = DiscordMcpConfig.toolServiceTypes().stream()
                .map(type -> instantiateToolService(type, jda))
                .toArray();
        ToolCallbackProvider upstream = MethodToolCallbackProvider.builder()
                .toolObjects(toolServices)
                .build();
        McpToolPolicy policy = policy(jda, ALLOWED_GUILD, "", "preview", "");
        Set<String> hiddenByDefault = new LinkedHashSet<>(McpToolPolicy.globalTargetToolNames());
        hiddenByDefault.addAll(McpToolPolicy.explicitOptInCredentialToolNames());

        assertThat(policy.apply(upstream).getToolCallbacks())
                .hasSize(upstream.getToolCallbacks().length
                        - hiddenByDefault.size())
                .extracting(callback -> callback.getToolDefinition().name())
                .doesNotContainAnyElementsOf(hiddenByDefault);
    }

    @Test
    void globalTargetToolsCannotAcquireGuildDefaultAuthorization() {
        Set<String> globalTargetTools = McpToolPolicy.globalTargetToolNames();
        Map<String, Set<String>> parametersByTool = DiscordMcpConfig.toolServiceTypes().stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .filter(method -> method.getAnnotation(Tool.class) != null)
                .collect(Collectors.toMap(
                        method -> method.getAnnotation(Tool.class).name(),
                        method -> Arrays.stream(method.getParameters())
                                .map(parameter -> parameter.getName())
                                .collect(Collectors.toSet())));

        assertThat(parametersByTool.keySet()).containsAll(globalTargetTools);
        globalTargetTools.forEach(tool -> assertThat(parametersByTool.get(tool))
                .as(tool + " must remain globally unresolvable under a guild allowlist")
                .doesNotContain("guildId"));
    }

    @Test
    void guildAllowlistHidesImplicitGlobalTargetTools() {
        McpToolPolicy policy = policy(jdaWithChannel(), ALLOWED_GUILD, "", "allow", "");
        ToolCallbackProvider raw = ToolCallbackProvider.from(
                callback("read_messages", new AtomicInteger()),
                callback("get_invite_details", new AtomicInteger()));

        assertThat(policy.apply(raw).getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("read_messages");
    }

    @Test
    void policyActiveWithoutToolAllowlistHidesCredentialTools() {
        McpToolPolicy policy = policy(jdaWithChannel(), "", "", "preview", "");
        ToolCallbackProvider raw = ToolCallbackProvider.from(
                callback("read_messages", new AtomicInteger()),
                callback("create_webhook", new AtomicInteger()),
                callback("list_webhooks", new AtomicInteger()),
                callback("create_invite", new AtomicInteger()),
                callback("list_invites", new AtomicInteger()),
                callback("get_invite_details", new AtomicInteger()),
                callback("read_private_messages", new AtomicInteger()));

        assertThat(policy.apply(raw).getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("read_messages");
    }

    @Test
    void exactToolAllowlistCanOptIntoGuildScopedCredentialTools() {
        McpToolPolicy policy = policy(jdaWithChannel(), ALLOWED_GUILD,
                "create_webhook,list_webhooks", "preview", "");

        ToolCallbackProvider raw = ToolCallbackProvider.from(
                callback("create_webhook", new AtomicInteger()),
                callback("list_webhooks", new AtomicInteger()));
        assertThat(policy.apply(raw).getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("create_webhook", "list_webhooks");
    }

    @Test
    void malformedGuildIdReturnsAnActionableInputError() {
        McpToolPolicy policy = policy(jdaWithChannel(), ALLOWED_GUILD,
                "list_channels", "allow", "");

        assertThatThrownBy(() -> only(policy.apply(provider("list_channels", new AtomicInteger())))
                .call("{\"guildId\":\"not-a-snowflake\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("17-20 digit Discord snowflake");
    }

    @Test
    void guildAllowlistRejectsExplicitGlobalTargetTools() {
        McpToolPolicy policy = policy(jdaWithChannel(), ALLOWED_GUILD,
                "get_invite_details", "allow", "");

        assertThatThrownBy(() -> policy.apply(provider("get_invite_details", new AtomicInteger())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot prove guild scope");
    }

    private static McpToolPolicy policy(JDA jda, String guilds, String tools, String mode, String audit) {
        return policy(jda, guilds, tools, mode, audit, "");
    }

    private static McpToolPolicy policy(JDA jda, String guilds, String tools, String mode,
                                        String audit, String operationalLog) {
        return new McpToolPolicy(jda, new ObjectMapper(), guilds, tools, "", mode, audit,
                "10485760", "", "", operationalLog);
    }

    private static Object instantiateToolService(Class<?> type, JDA jda) {
        try {
            return type.getConstructor(JDA.class).newInstance(jda);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Could not instantiate tool service " + type.getName(), error);
        }
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
                    case "list_webhooks" -> schema("channelId");
                    case "create_webhook" -> schema("channelId", "name");
                    case "create_invite" -> schema("guildId", "channelId");
                    case "list_invites" -> schema("guildId");
                    case "read_private_messages" -> schema("userId");
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
