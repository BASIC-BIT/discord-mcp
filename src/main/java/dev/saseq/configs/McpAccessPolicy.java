package dev.saseq.configs;

import dev.saseq.DiscordSnowflake;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Optional deployment guardrails for the generic Discord tool surface.
 *
 * <p>This class deliberately knows nothing about operators, approval workflows, read-versus-write
 * policy, or named Discord servers. Those decisions belong in the MCP client or a policy facade.
 * When configured, this layer only limits which tools are exported and which Discord guilds their
 * declared targets may resolve to.</p>
 */
@Component
public final class McpAccessPolicy {
    // A 50 MiB upload becomes 69,905,068 base64 characters. Keep guild scoping compatible with
    // that documented tool contract while retaining hard per-string and whole-document bounds.
    static final int MAX_ARGUMENT_STRING_CHARACTERS = 70_000_000;
    static final long MAX_ARGUMENT_DOCUMENT_CHARACTERS = 70_100_000L;
    private static final String TARGET_ACCESS_DENIED =
            "Discord target is unavailable or outside the allowed guild scope";
    private static final Set<String> REVIEWED_NON_CHANNEL_ID_ARGUMENTS = Set.of(
            "add_reaction.messageId",
            "assign_role.roleId", "assign_role.userId",
            "ban_member.userId",
            "delete_channel_permission_overwrite.targetId",
            "delete_emoji.emojiId",
            "delete_guild_scheduled_event.eventId",
            "delete_message.messageId",
            "delete_private_message.messageId", "delete_private_message.userId",
            "delete_role.roleId",
            "delete_webhook.webhookId",
            "disconnect_member.userId",
            "download_attachment.attachmentId", "download_attachment.messageId",
            "edit_emoji.emojiId",
            "edit_guild_scheduled_event.eventId",
            "edit_message.messageId",
            "edit_private_message.messageId", "edit_private_message.userId",
            "edit_role.roleId",
            "get_attachment.attachmentId", "get_attachment.messageId",
            "get_emoji_details.emojiId",
            "get_guild_scheduled_event_users.eventId",
            "get_member_by_id.userId",
            "get_message.messageId",
            "kick_member.userId",
            "modify_voice_state.userId",
            "move_member.userId",
            "read_private_messages.userId",
            "remove_reaction.messageId",
            "remove_role.roleId", "remove_role.userId",
            "remove_timeout.userId",
            "send_private_message.userId",
            "set_guild_scheduled_event_image.eventId",
            "set_nickname.userId",
            "timeout_member.userId",
            "unban_member.userId",
            "upsert_member_channel_permissions.userId",
            "upsert_role_channel_permissions.roleId");

    private final JDA jda;
    private final ObjectMapper objectMapper;
    private final ObjectMapper argumentObjectMapper;
    private final Set<String> allowedGuilds;
    private final Set<String> allowedTools;
    private final String defaultGuildId;

    public McpAccessPolicy(
            JDA jda,
            ObjectMapper objectMapper,
            @Value("${DISCORD_MCP_ALLOWED_GUILDS:}") String allowedGuilds,
            @Value("${DISCORD_MCP_ALLOWED_TOOLS:}") String allowedTools,
            @Value("${DISCORD_GUILD_ID:}") String defaultGuildId) {
        this.jda = jda;
        this.objectMapper = objectMapper;
        this.argumentObjectMapper = createArgumentObjectMapper(objectMapper);
        this.allowedGuilds = parseCsv(allowedGuilds, "DISCORD_MCP_ALLOWED_GUILDS");
        this.allowedTools = parseCsv(allowedTools, "DISCORD_MCP_ALLOWED_TOOLS");
        this.defaultGuildId = trimToNull(defaultGuildId);
        validateParsedConfiguration(this.allowedGuilds, this.defaultGuildId);
    }

    static void validateConfiguration(String allowedGuilds, String allowedTools,
                                      String defaultGuildId) {
        Set<String> parsedGuilds = parseCsv(allowedGuilds, "DISCORD_MCP_ALLOWED_GUILDS");
        parseCsv(allowedTools, "DISCORD_MCP_ALLOWED_TOOLS");
        validateParsedConfiguration(parsedGuilds, trimToNull(defaultGuildId));
    }

    static ObjectMapper createArgumentObjectMapper(ObjectMapper objectMapper) {
        var argumentFactory = objectMapper.tokenStreamFactory().rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxStringLength(MAX_ARGUMENT_STRING_CHARACTERS)
                        .maxDocumentLength(MAX_ARGUMENT_DOCUMENT_CHARACTERS)
                        .build())
                .build();
        return new ObjectMapper(argumentFactory);
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

        ToolCallback[] selected = Arrays.stream(raw)
                .filter(callback -> allowedTools.isEmpty()
                        || allowedTools.contains(callback.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);

        if (allowedGuilds.isEmpty()) {
            return ToolCallbackProvider.from(selected);
        }

        Set<String> omitted = new LinkedHashSet<>();
        ToolCallback[] scoped = Arrays.stream(selected)
                .map(callback -> scopeIfResolvable(callback, omitted))
                .filter(Objects::nonNull)
                .toArray(ToolCallback[]::new);
        if (!omitted.isEmpty()) {
            System.err.println("Discord guild scope omitted tools with no guild-resolvable target: "
                    + omitted);
        }
        return ToolCallbackProvider.from(scoped);
    }

    private ToolCallback scopeIfResolvable(ToolCallback delegate, Set<String> omitted) {
        Set<String> declared = schemaProperties(delegate.getToolDefinition());
        requireReviewedIdArguments(delegate.getToolDefinition().name(), declared);
        boolean resolvable = declared.contains("guildId")
                || declared.stream().anyMatch(McpAccessPolicy::isGuildChannelArgument);
        if (resolvable) {
            return new GuildScopedToolCallback(delegate, declared);
        }
        if (allowedTools.contains(delegate.getToolDefinition().name())) {
            throw startupError("Tool " + delegate.getToolDefinition().name()
                    + " has no guild-resolvable target and cannot be explicitly exported under "
                    + "DISCORD_MCP_ALLOWED_GUILDS");
        }
        omitted.add(delegate.getToolDefinition().name());
        return null;
    }

    private final class GuildScopedToolCallback implements ToolCallback {
        private final ToolCallback delegate;
        private final Set<String> declaredArguments;

        private GuildScopedToolCallback(ToolCallback delegate, Set<String> declaredArguments) {
            this.delegate = delegate;
            this.declaredArguments = declaredArguments;
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
            enforceGuildScope(arguments);
            return delegate.call(normalizeAbsentArguments(arguments));
        }

        @Override
        public String call(String arguments, ToolContext toolContext) {
            enforceGuildScope(arguments);
            return delegate.call(normalizeAbsentArguments(arguments), toolContext);
        }

        private void enforceGuildScope(String arguments) {
            Map<String, TargetArgument> parsed = parseTargetArguments(arguments, declaredArguments);
            Set<String> guildIds = resolveGuildIds(parsed, declaredArguments);
            if (guildIds.isEmpty() || !allowedGuilds.containsAll(guildIds)) {
                throw new SecurityException(TARGET_ACCESS_DENIED);
            }
        }
    }

    private Set<String> resolveGuildIds(Map<String, TargetArgument> arguments,
                                        Set<String> declaredArguments) {
        Set<String> guildIds = new LinkedHashSet<>();
        if (declaredArguments.contains("guildId")) {
            TargetArgument guildValue = arguments.get("guildId");
            boolean absent = guildValue == null || guildValue.token() == JsonToken.VALUE_NULL
                    || (guildValue.token() == JsonToken.VALUE_STRING
                        && guildValue.text().isEmpty());
            if (!absent) {
                guildIds.add(requireSnowflakeText(guildValue, "guildId"));
            } else if (defaultGuildId != null) {
                guildIds.add(defaultGuildId);
            }
        }

        for (String field : declaredArguments) {
            if (!isGuildChannelArgument(field)) {
                continue;
            }
            TargetArgument value = arguments.get(field);
            if (value == null || value.token() == JsonToken.VALUE_NULL) {
                continue;
            }
            // Discord edit tools use an empty optional category ID to detach a channel.
            // It contributes no guild evidence; another declared target must still establish scope.
            if (value.token() == JsonToken.VALUE_STRING && value.text().isEmpty()) {
                continue;
            }
            String channelId = requireSnowflakeText(value, field);
            GuildChannel channel = null;
            try {
                channel = jda.getGuildChannelById(channelId);
                if (channel == null) {
                    channel = jda.getThreadChannelById(channelId);
                }
            } catch (RuntimeException ignored) {
                // Invalid and uncached identifiers use the same non-disclosing denial.
            }
            if (channel == null) {
                throw new SecurityException(TARGET_ACCESS_DENIED);
            }
            guildIds.add(channel.getGuild().getId());
        }
        return guildIds;
    }

    private Map<String, TargetArgument> parseTargetArguments(
            String arguments, Set<String> declaredArguments) {
        if (arguments == null || arguments.isBlank() || "null".equals(arguments.strip())) {
            return Map.of();
        }
        try {
            Set<String> targetArguments = declaredArguments.stream()
                    .filter(field -> "guildId".equals(field) || isGuildChannelArgument(field))
                    .collect(Collectors.toSet());
            Map<String, TargetArgument> parsed = new LinkedHashMap<>();
            try (JsonParser parser = argumentObjectMapper.tokenStreamFactory()
                    .createParser(arguments)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    throw new IllegalArgumentException("Tool arguments must be a JSON object");
                }
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.PROPERTY_NAME) {
                        throw new IllegalArgumentException("Tool arguments are not valid JSON");
                    }
                    String field = parser.currentName();
                    JsonToken valueToken = parser.nextToken();
                    if (valueToken == null) {
                        throw new IllegalArgumentException("Tool arguments are not valid JSON");
                    }
                    if (targetArguments.contains(field)) {
                        String text = valueToken == JsonToken.VALUE_STRING ? parser.getText() : null;
                        parsed.put(field, new TargetArgument(valueToken, text));
                    }
                    parser.skipChildren();
                }
                if (parser.nextToken() != null) {
                    throw new IllegalArgumentException("Tool arguments must contain one JSON object");
                }
            }
            return parsed;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Tool arguments are not valid JSON", error);
        }
    }

    private record TargetArgument(JsonToken token, String text) {
    }

    private Set<String> schemaProperties(ToolDefinition definition) {
        try {
            JsonNode schema = objectMapper.readTree(definition.inputSchema());
            JsonNode properties = schema == null ? null : schema.get("properties");
            if (properties == null || properties.isNull()) {
                return Set.of();
            }
            if (!properties.isObject()) {
                throw startupError("Tool " + definition.name()
                        + " has no object properties in its input schema");
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

    static boolean isGuildChannelArgument(String field) {
        String normalized = field.toLowerCase(Locale.ROOT);
        return normalized.endsWith("id") && (normalized.contains("channel")
                || normalized.contains("category") || normalized.contains("forum")
                || normalized.contains("thread") || normalized.contains("post"));
    }

    private static void requireReviewedIdArguments(String toolName, Set<String> declared) {
        Set<String> unreviewed = declared.stream()
                .filter(field -> field.endsWith("Id"))
                .filter(field -> !"guildId".equals(field))
                .filter(field -> !isGuildChannelArgument(field))
                .filter(field -> !REVIEWED_NON_CHANNEL_ID_ARGUMENTS.contains(
                        toolName + "." + field))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!unreviewed.isEmpty()) {
            throw startupError("Tool " + toolName + " declares unreviewed Discord ID targets: "
                    + unreviewed);
        }
    }

    private static String requireSnowflakeText(TargetArgument value, String name) {
        if (value.token() != JsonToken.VALUE_STRING || !DiscordSnowflake.isValid(value.text())) {
            throw new IllegalArgumentException(name
                    + " must be a 17-20 digit Discord snowflake encoded as a JSON string");
        }
        return value.text();
    }

    private static Set<String> parseCsv(String raw, String name) {
        if (raw == null || raw.isEmpty()) {
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
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void requireSnowflake(String value, String name) {
        if (!DiscordSnowflake.isValid(value)) {
            throw startupError(name + " entries must be 17-20 digit Discord snowflakes");
        }
    }

    private static void validateParsedConfiguration(Set<String> allowedGuilds,
                                                    String defaultGuildId) {
        allowedGuilds.forEach(id -> requireSnowflake(id, "DISCORD_MCP_ALLOWED_GUILDS"));
        if (!allowedGuilds.isEmpty() && defaultGuildId != null) {
            requireSnowflake(defaultGuildId, "DISCORD_GUILD_ID");
            if (!allowedGuilds.contains(defaultGuildId)) {
                throw startupError("DISCORD_GUILD_ID must be in DISCORD_MCP_ALLOWED_GUILDS");
            }
        }
    }

    private static String normalizeAbsentArguments(String arguments) {
        return arguments == null || arguments.isBlank() || "null".equals(arguments.strip())
                ? "{}" : arguments;
    }

    private static IllegalArgumentException startupError(String message) {
        System.err.println("ERROR: " + message);
        return new IllegalArgumentException(message);
    }
}
