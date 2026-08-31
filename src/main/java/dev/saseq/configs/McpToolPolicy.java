package dev.saseq.configs;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deployment policy around every exported MCP tool.
 *
 * <p>The Discord permission model remains the final enforcement boundary, but this wrapper makes
 * the model-visible surface fail closed before a tool reaches JDA. Existing deployments are
 * backward compatible when none of the DISCORD_MCP_* policy variables are set.</p>
 */
@Component
public final class McpToolPolicy {
    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "get_server_info", "find_channel", "list_channels", "get_channel_info",
            "find_category", "list_channels_in_category", "list_channel_permission_overwrites",
            "list_roles", "get_member_by_id", "get_user_id_by_name", "search_members",
            "read_messages", "get_attachment", "list_guild_scheduled_events",
            "get_guild_scheduled_event_users", "list_active_threads", "list_forum_channels",
            "get_forum_channel_info", "list_forum_tags", "list_forum_posts", "list_emojis",
            "get_emoji_details", "list_webhooks", "list_invites", "get_invite_details",
            "get_bans", "read_private_messages"
    );
    private static final Set<String> WRITE_TOOLS = Set.of(
            "add_reaction", "assign_role", "ban_member", "create_category", "create_emoji",
            "create_forum_channel", "create_forum_post", "create_guild_scheduled_event",
            "create_invite", "create_role", "create_stage_channel", "create_text_channel",
            "create_voice_channel", "create_webhook", "delete_category", "delete_channel",
            "delete_channel_permission_overwrite", "delete_emoji", "delete_guild_scheduled_event",
            "delete_invite", "delete_message", "delete_private_message", "delete_role",
            "delete_webhook", "disconnect_member", "download_attachment", "edit_category",
            "edit_emoji", "edit_forum_channel", "edit_guild_scheduled_event", "edit_message",
            "edit_private_message", "edit_role", "edit_text_channel", "edit_voice_channel",
            "kick_member", "modify_forum_post", "modify_voice_state", "move_channel",
            "move_member", "remove_reaction", "remove_role", "remove_timeout", "send_file",
            "send_message", "send_private_message", "send_webhook_message",
            "set_guild_scheduled_event_image", "set_nickname", "timeout_member", "unban_member",
            "upsert_member_channel_permissions", "upsert_role_channel_permissions"
    );
    private static final Set<String> CHANNEL_ID_FIELDS = Set.of(
            "channelId", "categoryId", "forumChannelId", "parentChannelId", "threadId"
    );

    private final JDA jda;
    private final ObjectMapper objectMapper;
    private final Set<String> allowedGuilds;
    private final Set<String> allowedTools;
    private final String defaultGuildId;
    private final WriteMode writeMode;
    private final Path auditFile;
    private final long auditMaxBytes;

    public McpToolPolicy(
            JDA jda,
            ObjectMapper objectMapper,
            @Value("${DISCORD_MCP_ALLOWED_GUILDS:}") String allowedGuilds,
            @Value("${DISCORD_MCP_ALLOWED_TOOLS:}") String allowedTools,
            @Value("${DISCORD_GUILD_ID:}") String defaultGuildId,
            @Value("${DISCORD_MCP_WRITE_MODE:allow}") String writeMode,
            @Value("${DISCORD_MCP_AUDIT_FILE:}") String auditFile,
            @Value("${DISCORD_MCP_AUDIT_MAX_BYTES:10485760}") long auditMaxBytes) {
        this.jda = jda;
        this.objectMapper = objectMapper;
        this.allowedGuilds = parseCsv(allowedGuilds, "DISCORD_MCP_ALLOWED_GUILDS");
        this.allowedTools = parseCsv(allowedTools, "DISCORD_MCP_ALLOWED_TOOLS");
        this.defaultGuildId = trimToNull(defaultGuildId);
        this.writeMode = WriteMode.parse(writeMode);
        this.auditFile = trimToNull(auditFile) == null ? null : Path.of(auditFile).toAbsolutePath().normalize();
        if (auditMaxBytes < 1024) {
            throw startupError("DISCORD_MCP_AUDIT_MAX_BYTES must be at least 1024");
        }
        this.auditMaxBytes = auditMaxBytes;

        this.allowedGuilds.forEach(id -> requireSnowflake(id, "DISCORD_MCP_ALLOWED_GUILDS"));
        if (this.defaultGuildId != null) {
            requireSnowflake(this.defaultGuildId, "DISCORD_GUILD_ID");
            if (!this.allowedGuilds.isEmpty() && !this.allowedGuilds.contains(this.defaultGuildId)) {
                throw startupError("DISCORD_GUILD_ID must be in DISCORD_MCP_ALLOWED_GUILDS");
            }
        }
    }

    ToolCallbackProvider apply(ToolCallbackProvider rawProvider) {
        ToolCallback[] raw = rawProvider.getToolCallbacks();
        Set<String> available = Arrays.stream(raw)
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());
        Set<String> unknown = new LinkedHashSet<>(allowedTools);
        unknown.removeAll(available);
        if (!unknown.isEmpty()) {
            throw startupError("DISCORD_MCP_ALLOWED_TOOLS contains unknown tools: " + unknown);
        }
        ToolCallback[] filtered = Arrays.stream(raw)
                .filter(callback -> allowedTools.isEmpty()
                        || allowedTools.contains(callback.getToolDefinition().name()))
                .map(PolicyToolCallback::new)
                .toArray(ToolCallback[]::new);
        return ToolCallbackProvider.from(filtered);
    }

    private final class PolicyToolCallback implements ToolCallback {
        private final ToolCallback delegate;
        private final Set<String> declaredArguments;

        private PolicyToolCallback(ToolCallback delegate) {
            this.delegate = delegate;
            this.declaredArguments = schemaProperties(delegate.getToolDefinition());
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String arguments) {
            return invoke(arguments, null);
        }

        @Override
        public String call(String arguments, ToolContext toolContext) {
            return invoke(arguments, toolContext);
        }

        private String invoke(String arguments, ToolContext toolContext) {
            String tool = getToolDefinition().name();
            JsonNode parsed = parseArguments(arguments);
            if (!allowedGuilds.isEmpty()) {
                rejectUndeclaredArguments(tool, parsed, declaredArguments);
            }
            Set<String> guildIds;
            try {
                guildIds = resolveGuildIds(parsed, declaredArguments, !allowedGuilds.isEmpty());
            } catch (SecurityException error) {
                audit(tool, "denied-unresolved-channel", Set.of(), parsed, null);
                throw error;
            }
            enforceGuilds(tool, guildIds, parsed);

            if (writeMode == WriteMode.PREVIEW && !READ_ONLY_TOOLS.contains(tool)) {
                audit(tool, "preview", guildIds, parsed, null);
                return "WRITE_PREVIEW: " + tool + " was not called. Arguments: "
                        + previewArguments(arguments, parsed);
            }

            audit(tool, "started", guildIds, parsed, null);
            try {
                String result = toolContext == null
                        ? delegate.call(arguments)
                        : delegate.call(arguments, toolContext);
                String auditWarning = auditBestEffort(tool, "executed", guildIds, parsed, null);
                return auditWarning == null ? result : result + System.lineSeparator() + auditWarning;
            } catch (RuntimeException error) {
                auditBestEffort(tool, "failed", guildIds, parsed, error.getClass().getSimpleName());
                throw error;
            }
        }
    }

    private JsonNode parseArguments(String arguments) {
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(arguments);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Tool arguments are not valid JSON", error);
        }
        if (parsed == null || !parsed.isObject()) {
            throw new IllegalArgumentException("Tool arguments must be a JSON object");
        }
        return parsed;
    }

    private Set<String> resolveGuildIds(JsonNode arguments, Set<String> declaredArguments,
                                        boolean failOnUnresolvedChannel) {
        Set<String> guildIds = new LinkedHashSet<>();
        JsonNode guildValue = arguments.get("guildId");
        if (guildValue != null && !guildValue.isNull() && !guildValue.isTextual()) {
            throw new SecurityException("Supplied guildId must be a string");
        }
        addText(arguments, "guildId", guildIds);
        if (guildIds.isEmpty() && declaredArguments.contains("guildId") && defaultGuildId != null) {
            guildIds.add(defaultGuildId);
        }

        for (String field : CHANNEL_ID_FIELDS) {
            JsonNode value = arguments.get(field);
            if (value == null || value.isNull() || value.isTextual() && value.asText().isBlank()) {
                continue;
            }
            if (!value.isTextual()) {
                throw new SecurityException("Supplied " + field + " must be a JSON string");
            }
            GuildChannel channel = jda.getGuildChannelById(value.asText());
            if (channel == null) {
                channel = jda.getThreadChannelById(value.asText());
            }
            if (channel != null) {
                guildIds.add(channel.getGuild().getId());
            } else if (failOnUnresolvedChannel && guildIds.isEmpty()) {
                throw new SecurityException("Supplied " + field
                        + " is not cached and this tool has no explicit allowed guild target");
            }
        }
        return guildIds;
    }

    private void enforceGuilds(String tool, Set<String> guildIds, JsonNode arguments) {
        if (allowedGuilds.isEmpty()) {
            return;
        }
        if (guildIds.isEmpty()) {
            audit(tool, "denied-unresolved-guild", guildIds, arguments, null);
            throw new SecurityException("Guild could not be resolved for allowlisted tool call: " + tool);
        }
        Set<String> denied = new LinkedHashSet<>(guildIds);
        denied.removeAll(allowedGuilds);
        if (!denied.isEmpty()) {
            audit(tool, "denied-guild", guildIds, arguments, null);
            throw new SecurityException("Guild is not in DISCORD_MCP_ALLOWED_GUILDS");
        }
    }

    private synchronized void audit(String tool, String outcome, Set<String> guildIds,
                                    JsonNode arguments, String errorType) {
        if (auditFile == null) {
            return;
        }
        try {
            Path parent = auditFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            var event = objectMapper.createObjectNode();
            event.put("timestamp", Instant.now().toString());
            event.put("tool", tool);
            event.put("outcome", outcome);
            event.put("writeMode", writeMode.name().toLowerCase(Locale.ROOT));
            var guildArray = event.putArray("guildIds");
            guildIds.forEach(guildArray::add);
            if (arguments != null) {
                arguments.properties().forEach(entry -> {
                    if (entry.getKey().matches("(?i).*(id|code)$")
                            && entry.getValue().isValueNode() && !entry.getValue().isNull()) {
                        event.put(entry.getKey(), entry.getValue().asText());
                    }
                });
                event.put("argumentsSha256", sha256(arguments.toString()));
            }
            if (errorType != null) {
                event.put("errorType", errorType);
            }
            String line = objectMapper.writeValueAsString(event) + System.lineSeparator();
            rotateAuditIfNeeded(line.getBytes(StandardCharsets.UTF_8).length);
            Files.writeString(auditFile, line,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException error) {
            throw new IllegalStateException("Could not append DISCORD_MCP_AUDIT_FILE", error);
        }
    }

    private String auditBestEffort(String tool, String outcome, Set<String> guildIds,
                                   JsonNode arguments, String errorType) {
        try {
            audit(tool, outcome, guildIds, arguments, errorType);
            return null;
        } catch (RuntimeException auditError) {
            String warning = "WARNING: The tool outcome is preserved, but its audit completion record failed: "
                    + auditError.getMessage();
            System.err.println(warning);
            return warning;
        }
    }

    private void rotateAuditIfNeeded(int nextLineBytes) throws IOException {
        if (!Files.exists(auditFile) || Files.size(auditFile) + nextLineBytes <= auditMaxBytes) {
            return;
        }
        Path rotated = auditFile.resolveSibling(auditFile.getFileName() + ".1");
        Files.move(auditFile, rotated, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void addText(JsonNode source, String field, Set<String> values) {
        JsonNode value = source.get(field);
        if (value != null && value.isTextual() && !value.asText().isBlank()) {
            values.add(value.asText());
        }
    }

    private Set<String> schemaProperties(ToolDefinition definition) {
        try {
            JsonNode schema = objectMapper.readTree(definition.inputSchema());
            JsonNode properties = schema == null ? null : schema.get("properties");
            if (properties == null || !properties.isObject()) {
                throw startupError("Tool " + definition.name() + " has no object properties in its input schema");
            }
            Set<String> names = new LinkedHashSet<>();
            properties.propertyNames().forEach(names::add);
            return Set.copyOf(names);
        } catch (RuntimeException error) {
            if (error instanceof IllegalArgumentException) {
                throw error;
            }
            throw startupError("Tool " + definition.name() + " has an invalid input schema");
        }
    }

    private static void rejectUndeclaredArguments(String tool, JsonNode arguments, Set<String> declared) {
        Set<String> supplied = new LinkedHashSet<>();
        arguments.propertyNames().forEach(supplied::add);
        supplied.removeAll(declared);
        if (!supplied.isEmpty()) {
            throw new SecurityException("Tool " + tool + " received undeclared arguments: " + supplied);
        }
    }

    private String previewArguments(String raw, JsonNode parsed) {
        final int maximumCharacters = 16_384;
        if (raw.length() <= maximumCharacters) {
            return raw;
        }
        JsonNode fileData = parsed.get("fileData");
        if (fileData != null && fileData.isTextual()) {
            var redacted = parsed.deepCopy();
            String value = fileData.asText();
            ((tools.jackson.databind.node.ObjectNode) redacted).put("fileData",
                    "<omitted " + value.length() + " characters; sha256=" + sha256(value) + ">");
            String compact = redacted.toString();
            if (compact.length() <= maximumCharacters) {
                return compact;
            }
        }
        return raw.substring(0, maximumCharacters)
                + "<preview truncated; full arguments sha256=" + sha256(raw) + ">";
    }

    private static Set<String> parseCsv(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> values = Arrays.stream(raw.split(",", -1))
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (values.contains("")) {
            throw startupError(name + " contains an empty entry");
        }
        return Set.copyOf(values);
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static IllegalArgumentException startupError(String message) {
        System.err.println("ERROR: Discord MCP policy configuration is invalid: " + message);
        return new IllegalArgumentException(message);
    }

    static Set<String> readOnlyToolNames() {
        return READ_ONLY_TOOLS;
    }

    static Set<String> writeToolNames() {
        return WRITE_TOOLS;
    }

    private static void requireSnowflake(String value, String name) {
        if (!value.matches("\\d{17,20}")) {
            throw startupError(name + " entries must be 17-20 digit Discord snowflakes");
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private enum WriteMode {
        ALLOW,
        PREVIEW;

        private static WriteMode parse(String value) {
            try {
                return WriteMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException error) {
                throw startupError("DISCORD_MCP_WRITE_MODE must be 'allow' or 'preview'");
            }
        }
    }
}
