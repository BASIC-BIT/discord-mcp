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
            "get_bans"
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

    public McpToolPolicy(
            JDA jda,
            ObjectMapper objectMapper,
            @Value("${DISCORD_MCP_ALLOWED_GUILDS:}") String allowedGuilds,
            @Value("${DISCORD_MCP_ALLOWED_TOOLS:}") String allowedTools,
            @Value("${DISCORD_GUILD_ID:}") String defaultGuildId,
            @Value("${DISCORD_MCP_WRITE_MODE:allow}") String writeMode,
            @Value("${DISCORD_MCP_AUDIT_FILE:}") String auditFile) {
        this.jda = jda;
        this.objectMapper = objectMapper;
        this.allowedGuilds = parseCsv(allowedGuilds, "DISCORD_MCP_ALLOWED_GUILDS");
        this.allowedTools = parseCsv(allowedTools, "DISCORD_MCP_ALLOWED_TOOLS");
        this.defaultGuildId = trimToNull(defaultGuildId);
        this.writeMode = WriteMode.parse(writeMode);
        this.auditFile = trimToNull(auditFile) == null ? null : Path.of(auditFile).toAbsolutePath().normalize();

        this.allowedGuilds.forEach(id -> requireSnowflake(id, "DISCORD_MCP_ALLOWED_GUILDS"));
        if (this.defaultGuildId != null) {
            requireSnowflake(this.defaultGuildId, "DISCORD_GUILD_ID");
            if (!this.allowedGuilds.isEmpty() && !this.allowedGuilds.contains(this.defaultGuildId)) {
                throw new IllegalArgumentException("DISCORD_GUILD_ID must be in DISCORD_MCP_ALLOWED_GUILDS");
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
            throw new IllegalArgumentException("DISCORD_MCP_ALLOWED_TOOLS contains unknown tools: " + unknown);
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

        private PolicyToolCallback(ToolCallback delegate) {
            this.delegate = delegate;
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
            Set<String> guildIds = resolveGuildIds(parsed);
            enforceGuilds(tool, guildIds);

            if (writeMode == WriteMode.PREVIEW && !READ_ONLY_TOOLS.contains(tool)) {
                audit(tool, "preview", guildIds, parsed, null);
                return "WRITE_PREVIEW: " + tool + " was not called. Arguments: " + arguments;
            }

            try {
                String result = toolContext == null
                        ? delegate.call(arguments)
                        : delegate.call(arguments, toolContext);
                audit(tool, "executed", guildIds, parsed, null);
                return result;
            } catch (RuntimeException error) {
                audit(tool, "failed", guildIds, parsed, error.getClass().getSimpleName());
                throw error;
            }
        }
    }

    private JsonNode parseArguments(String arguments) {
        try {
            JsonNode parsed = objectMapper.readTree(arguments);
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalArgumentException("Tool arguments must be a JSON object");
            }
            return parsed;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Tool arguments are not valid JSON", error);
        }
    }

    private Set<String> resolveGuildIds(JsonNode arguments) {
        Set<String> guildIds = new LinkedHashSet<>();
        addText(arguments, "guildId", guildIds);

        for (String field : CHANNEL_ID_FIELDS) {
            JsonNode value = arguments.get(field);
            if (value == null || !value.isTextual() || value.asText().isBlank()) {
                continue;
            }
            GuildChannel channel = jda.getGuildChannelById(value.asText());
            if (channel != null) {
                guildIds.add(channel.getGuild().getId());
            }
        }

        if (guildIds.isEmpty() && defaultGuildId != null) {
            guildIds.add(defaultGuildId);
        }
        return guildIds;
    }

    private void enforceGuilds(String tool, Set<String> guildIds) {
        if (allowedGuilds.isEmpty()) {
            return;
        }
        if (guildIds.isEmpty()) {
            audit(tool, "denied-unresolved-guild", guildIds, null, null);
            throw new SecurityException("Guild could not be resolved for allowlisted tool call: " + tool);
        }
        Set<String> denied = new LinkedHashSet<>(guildIds);
        denied.removeAll(allowedGuilds);
        if (!denied.isEmpty()) {
            audit(tool, "denied-guild", guildIds, null, null);
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
                copyIdentifier(arguments, event, "channelId");
                copyIdentifier(arguments, event, "messageId");
                copyIdentifier(arguments, event, "eventId");
                event.put("argumentsSha256", sha256(arguments.toString()));
            }
            if (errorType != null) {
                event.put("errorType", errorType);
            }
            Files.writeString(auditFile, objectMapper.writeValueAsString(event) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException error) {
            throw new IllegalStateException("Could not append DISCORD_MCP_AUDIT_FILE", error);
        }
    }

    private static void addText(JsonNode source, String field, Set<String> values) {
        JsonNode value = source.get(field);
        if (value != null && value.isTextual() && !value.asText().isBlank()) {
            values.add(value.asText());
        }
    }

    private static void copyIdentifier(JsonNode source, tools.jackson.databind.node.ObjectNode target,
                                       String field) {
        JsonNode value = source.get(field);
        if (value != null && value.isTextual() && !value.asText().isBlank()) {
            target.put(field, value.asText());
        }
    }

    private static Set<String> parseCsv(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> values = Arrays.stream(raw.split(",", -1))
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (values.contains("")) {
            throw new IllegalArgumentException(name + " contains an empty entry");
        }
        return Set.copyOf(values);
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static void requireSnowflake(String value, String name) {
        if (!value.matches("\\d{17,20}")) {
            throw new IllegalArgumentException(name + " entries must be 17-20 digit Discord snowflakes");
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
                throw new IllegalArgumentException("DISCORD_MCP_WRITE_MODE must be 'allow' or 'preview'");
            }
        }
    }
}
