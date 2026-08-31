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
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.DigestOutputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
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
    private static final String TARGET_ACCESS_DENIED =
            "Discord target is unavailable or outside the allowed guild scope";
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
    /** Classification inventory for tests; runtime treats every tool not listed read-only as a write. */
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
    private final JDA jda;
    private final ObjectMapper objectMapper;
    private final Set<String> allowedGuilds;
    private final Set<String> allowedTools;
    private final String defaultGuildId;
    private final WriteMode writeMode;
    private final boolean policyActive;
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
            @Value("${DISCORD_MCP_AUDIT_MAX_BYTES:10485760}") String auditMaxBytes) {
        this.jda = jda;
        this.objectMapper = objectMapper;
        this.allowedGuilds = parseCsv(allowedGuilds, "DISCORD_MCP_ALLOWED_GUILDS");
        this.allowedTools = parseCsv(allowedTools, "DISCORD_MCP_ALLOWED_TOOLS");
        this.defaultGuildId = trimToNull(defaultGuildId);
        this.writeMode = WriteMode.parse(writeMode);
        this.policyActive = !this.allowedGuilds.isEmpty() || !this.allowedTools.isEmpty()
                || this.writeMode == WriteMode.PREVIEW;
        String configuredAuditFile = trimToNull(auditFile);
        this.auditFile = configuredAuditFile == null
                ? null : Path.of(configuredAuditFile).toAbsolutePath().normalize();
        this.auditMaxBytes = this.auditFile == null ? 10_485_760L : parseAuditMaxBytes(auditMaxBytes);
        if (this.auditFile != null && this.auditMaxBytes < 1024) {
            throw startupError("DISCORD_MCP_AUDIT_MAX_BYTES must be at least 1024");
        }

        if (this.auditFile != null && this.auditFile.getParent() != null) {
            try {
                Files.createDirectories(this.auditFile.getParent());
            } catch (IOException error) {
                throw startupError("Could not create DISCORD_MCP_AUDIT_FILE parent directory");
            }
        }

        this.allowedGuilds.forEach(id -> requireSnowflake(id, "DISCORD_MCP_ALLOWED_GUILDS"));
        if (this.defaultGuildId != null && policyActive) {
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
            // Schema enforcement belongs to policy-active deployments. Audit-only and legacy
            // profiles keep upstream argument handling and cannot be broken by schema drift.
            this.declaredArguments = policyActive
                    ? schemaProperties(delegate.getToolDefinition()) : Set.of();
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
            String invocationId = UUID.randomUUID().toString();
            if (!policyActive && auditFile == null && arguments != null && !arguments.isBlank()) {
                return toolContext == null
                        ? delegate.call(arguments)
                        : delegate.call(arguments, toolContext);
            }
            // Hash the raw caller representation so the audit can correlate the original request,
            // while policy-active delegates receive the normalized tree inspected below.
            String argumentsForHash = arguments == null || arguments.isBlank() ? "{}" : arguments;
            String argumentsSha256 = sha256(argumentsForHash);
            JsonNode parsed;
            try {
                parsed = parseArguments(arguments);
            } catch (IllegalArgumentException error) {
                auditBestEffort(tool, "denied-invalid-arguments", invocationId, Set.of(), null,
                        argumentsSha256, error.getClass().getSimpleName());
                throw error;
            }
            if (policyActive) {
                try {
                    rejectUndeclaredArguments(tool, parsed, declaredArguments);
                } catch (SecurityException error) {
                    auditBestEffort(tool, "denied-undeclared-argument", invocationId,
                            Set.of(), null, argumentsSha256,
                            error.getClass().getSimpleName());
                    throw error;
                }
            }
            boolean normalizedTargetIdentifiers = !allowedGuilds.isEmpty()
                    && normalizePolicyTargetIdentifiers(parsed);
            Set<String> guildIds;
            try {
                guildIds = resolveGuildIds(parsed, declaredArguments, !allowedGuilds.isEmpty());
            } catch (SecurityException error) {
                auditBestEffort(tool, "denied-invalid-target", invocationId, Set.of(), parsed,
                        argumentsSha256, null);
                throw new SecurityException(TARGET_ACCESS_DENIED);
            }
            enforceGuilds(tool, invocationId, guildIds, parsed, argumentsSha256);

            if (writeMode == WriteMode.PREVIEW && !READ_ONLY_TOOLS.contains(tool)) {
                audit(tool, "preview", invocationId, guildIds, parsed, argumentsSha256, null);
                return "WRITE_PREVIEW: This deployment runs in preview mode; " + tool
                        + " was not called, and retrying here will produce the same result. Arguments: "
                        + previewArguments(argumentsForHash, parsed);
            }

            audit(tool, "started", invocationId, guildIds, parsed, argumentsSha256, null);
            try {
                String delegatedArguments = normalizedTargetIdentifiers
                        ? parsed.toString() : argumentsForHash;
                String result = toolContext == null
                        ? delegate.call(delegatedArguments)
                        : delegate.call(delegatedArguments, toolContext);
                String completionWarning = auditBestEffort(
                        tool, "tool-returned", invocationId, guildIds, parsed,
                        argumentsSha256, null);
                return completionWarning == null
                        ? result : result + System.lineSeparator() + completionWarning;
            } catch (RuntimeException error) {
                auditBestEffort(tool, "failed", invocationId, guildIds, parsed, argumentsSha256,
                        error.getClass().getSimpleName());
                throw error;
            }
        }
    }

    private JsonNode parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return objectMapper.createObjectNode();
        }
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(arguments);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Tool arguments are not valid JSON", error);
        }
        if (parsed == null || parsed.isNull() || parsed.isMissingNode()) {
            return objectMapper.createObjectNode();
        }
        if (!parsed.isObject()) {
            throw new IllegalArgumentException("Tool arguments must be a JSON object");
        }
        return parsed;
    }

    private Set<String> resolveGuildIds(JsonNode arguments, Set<String> declaredArguments,
                                        boolean failOnUnresolvedChannel) {
        Set<String> guildIds = new LinkedHashSet<>();
        JsonNode guildValue = arguments.get("guildId");
        if (failOnUnresolvedChannel && guildValue != null && !guildValue.isNull()) {
            if (!guildValue.isTextual()) {
                throw new SecurityException("Supplied guildId must be a string");
            }
            if (!guildValue.asText().isBlank() && !isSnowflake(guildValue.asText())) {
                throw new SecurityException("Supplied guildId must be a 17-20 digit Discord snowflake");
            }
        }
        addText(arguments, "guildId", guildIds);
        if (guildIds.isEmpty() && declaredArguments.contains("guildId") && defaultGuildId != null) {
            guildIds.add(defaultGuildId);
        }

        for (var entry : arguments.properties()) {
            String field = entry.getKey();
            if (!isGuildChannelArgument(field)) {
                continue;
            }
            JsonNode value = entry.getValue();
            if (value == null || value.isNull() || value.isTextual() && value.asText().isBlank()) {
                continue;
            }
            if (!value.isTextual()) {
                if (failOnUnresolvedChannel) {
                    throw new SecurityException("Supplied " + field + " must be a JSON string");
                }
                continue;
            }
            GuildChannel channel = null;
            try {
                channel = jda.getGuildChannelById(value.asText());
                if (channel == null) {
                    channel = jda.getThreadChannelById(value.asText());
                }
            } catch (IllegalArgumentException ignored) {
                // Malformed snowflakes are unresolved targets and must follow the audited denial path.
            }
            if (channel != null) {
                guildIds.add(channel.getGuild().getId());
            } else if (failOnUnresolvedChannel) {
                throw new SecurityException("Supplied " + field
                        + " is not cached; allowlisted calls require every supplied channel target to resolve");
            }
        }
        return guildIds;
    }

    private boolean normalizePolicyTargetIdentifiers(JsonNode arguments) {
        Set<String> fieldsToNormalize = new LinkedHashSet<>();
        for (var entry : arguments.properties()) {
            String field = entry.getKey();
            JsonNode value = entry.getValue();
            if (("guildId".equals(field) || isGuildChannelArgument(field))
                    && value != null && value.isIntegralNumber()) {
                fieldsToNormalize.add(field);
            }
        }
        var object = (tools.jackson.databind.node.ObjectNode) arguments;
        fieldsToNormalize.forEach(field -> object.put(field, object.get(field).asText()));
        return !fieldsToNormalize.isEmpty();
    }

    private void enforceGuilds(String tool, String invocationId, Set<String> guildIds,
                               JsonNode arguments, String argumentsSha256) {
        if (allowedGuilds.isEmpty()) {
            return;
        }
        if (guildIds.isEmpty()) {
            auditBestEffort(tool, "denied-unresolved-guild", invocationId, guildIds, arguments,
                    argumentsSha256, null);
            throw new SecurityException(TARGET_ACCESS_DENIED);
        }
        Set<String> denied = new LinkedHashSet<>(guildIds);
        denied.removeAll(allowedGuilds);
        if (!denied.isEmpty()) {
            auditBestEffort(tool, "denied-guild", invocationId, guildIds, arguments,
                    argumentsSha256, null);
            throw new SecurityException(TARGET_ACCESS_DENIED);
        }
    }

    // Expected operator traffic is low. Serializing the two append operations keeps each JSONL
    // record and size-based rotation atomic without retaining a file handle across rotations.
    private synchronized void audit(String tool, String outcome, String invocationId,
                                    Set<String> guildIds, JsonNode arguments,
                                    String argumentsSha256, String errorType) {
        if (auditFile == null) {
            return;
        }
        try {
            var event = objectMapper.createObjectNode();
            event.put("timestamp", Instant.now().toString());
            event.put("invocationId", invocationId);
            event.put("tool", tool);
            event.put("outcome", outcome);
            event.put("writeMode", writeMode.name().toLowerCase(Locale.ROOT));
            var guildArray = event.putArray("guildIds");
            guildIds.forEach(id -> guildArray.add(boundedAuditIdentifier(id)));
            if (arguments != null) {
                arguments.properties().forEach(entry -> {
                    if (entry.getKey().matches("(?i).*id$")
                            && entry.getValue().isValueNode() && !entry.getValue().isNull()) {
                        event.put(entry.getKey(), boundedAuditIdentifier(entry.getValue().asText()));
                    }
                });
            }
            if (argumentsSha256 != null) {
                event.put("argumentsSha256", argumentsSha256);
            }
            if (errorType != null) {
                event.put("errorType", errorType);
            }
            String line = event.toString() + System.lineSeparator();
            int lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
            if (lineBytes > auditMaxBytes) {
                throw new IllegalStateException("Audit record exceeds DISCORD_MCP_AUDIT_MAX_BYTES");
            }
            rotateAuditIfNeeded(lineBytes);
            Files.writeString(auditFile, line,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException error) {
            throw new IllegalStateException("Could not append DISCORD_MCP_AUDIT_FILE", error);
        }
    }

    private String auditBestEffort(String tool, String outcome, String invocationId,
                                   Set<String> guildIds, JsonNode arguments,
                                   String argumentsSha256, String errorType) {
        if (auditFile == null && outcome.startsWith("denied-")) {
            System.err.println("Discord MCP policy denied tool " + tool + " (" + outcome + ").");
            return null;
        }
        try {
            audit(tool, outcome, invocationId, guildIds, arguments, argumentsSha256, errorType);
            return null;
        } catch (RuntimeException auditError) {
            String warning = "WARNING: The tool outcome is preserved, but its audit completion record failed.";
            System.err.println(warning + " " + auditError.getMessage());
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

    static boolean isGuildChannelArgument(String field) {
        String normalized = field.toLowerCase(Locale.ROOT);
        boolean idShaped = normalized.endsWith("id") || normalized.endsWith("ids");
        return idShaped && (normalized.contains("channel")
                || normalized.contains("category") || normalized.contains("forum")
                || normalized.contains("thread") || normalized.contains("post"));
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
        final int largeValueCharacters = 4_096;
        var redacted = (tools.jackson.databind.node.ObjectNode) parsed.deepCopy();
        parsed.properties().forEach(entry -> {
            if (entry.getValue().isTextual()
                    && entry.getValue().asText().length() > largeValueCharacters) {
                String value = entry.getValue().asText();
                redacted.put(entry.getKey(),
                        "<omitted " + value.length() + " characters; sha256=" + sha256(value) + ">");
            }
        });
        String compact = redacted.toString();
        if (compact.length() <= maximumCharacters) {
            return compact;
        }
        String suffix = "<preview truncated; full arguments sha256=" + sha256(raw) + ">";
        return compact.substring(0, maximumCharacters - suffix.length()) + suffix;
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

    private static String boundedAuditIdentifier(String value) {
        final int maximumCharacters = 64;
        if (value.length() <= maximumCharacters) {
            return value;
        }
        return "<omitted " + value.length() + " characters; sha256=" + sha256(value) + ">";
    }

    private static long parseAuditMaxBytes(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return 10_485_760L;
        }
        try {
            return Long.parseLong(normalized);
        } catch (NumberFormatException error) {
            throw startupError("DISCORD_MCP_AUDIT_MAX_BYTES must be an integer");
        }
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
        if (!isSnowflake(value)) {
            throw startupError(name + " entries must be 17-20 digit Discord snowflakes");
        }
    }

    private static boolean isSnowflake(String value) {
        return value.matches("\\d{17,20}");
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (Writer writer = new OutputStreamWriter(
                    new DigestOutputStream(OutputStream.nullOutputStream(), digest),
                    StandardCharsets.UTF_8)) {
                writer.write(value);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException | IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private enum WriteMode {
        ALLOW,
        PREVIEW;

        private static WriteMode parse(String value) {
            String normalized = trimToNull(value);
            if (normalized == null) {
                return ALLOW;
            }
            try {
                return WriteMode.valueOf(normalized.toUpperCase(Locale.ROOT));
            } catch (RuntimeException error) {
                throw startupError("DISCORD_MCP_WRITE_MODE must be 'allow' or 'preview'");
            }
        }
    }
}
