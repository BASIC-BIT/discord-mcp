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
import tools.jackson.core.exc.StreamConstraintsException;
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
    // A 50 MiB upload becomes 69,905,068 base64 characters. Do not make this policy parser the
    // limiting layer for that documented tool contract; transport and delegate limits are separate.
    static final int MAX_ARGUMENT_STRING_CHARACTERS = 70_000_000;
    static final long MAX_ARGUMENT_DOCUMENT_CHARACTERS = 70_100_000L;
    private static final String TARGET_ACCESS_DENIED =
            "Discord target is unavailable or outside the allowed guild scope";
    private static final Set<String> EXPLICIT_ONLY_WHEN_GUILD_SCOPED = Set.of(
            "create_invite", "create_webhook", "list_webhooks");
    private static final Set<String> REVIEWED_CHANNEL_ID_ARGUMENTS = Set.of(
            "add_reaction.channelId",
            "create_forum_channel.categoryId",
            "create_forum_post.channelId",
            "create_guild_scheduled_event.channelId",
            "create_invite.channelId",
            "create_stage_channel.categoryId",
            "create_text_channel.categoryId",
            "create_voice_channel.categoryId",
            "create_webhook.channelId",
            "delete_category.categoryId",
            "delete_channel.channelId",
            "delete_channel_permission_overwrite.channelId",
            "delete_message.channelId",
            "download_attachment.channelId",
            "edit_category.categoryId",
            "edit_forum_channel.categoryId", "edit_forum_channel.channelId",
            "edit_message.channelId",
            "edit_text_channel.categoryId", "edit_text_channel.channelId",
            "edit_voice_channel.channelId",
            "get_attachment.channelId",
            "get_channel_info.channelId",
            "get_forum_channel_info.channelId",
            "get_message.channelId",
            "list_channel_permission_overwrites.channelId",
            "list_channels_in_category.categoryId",
            "list_forum_posts.channelId",
            "list_forum_tags.channelId",
            "modify_forum_post.postId",
            "move_channel.categoryId", "move_channel.channelId",
            "move_member.channelId",
            "read_messages.channelId",
            "remove_reaction.channelId",
            "send_file.channelId",
            "send_message.channelId",
            "upsert_member_channel_permissions.channelId",
            "upsert_role_channel_permissions.channelId");
    // This list can only classify names that advertise ID semantics. Schema reviews must also
    // inspect aliases such as webhookUrl, inviteCode, before/after/around, and comma-separated roles,
    // plus near-miss names such as crosspostMessageId or threadStarterMessageId that contain a
    // channel-shaped substring without identifying a channel.
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
            "create_forum_post.tagIds", "modify_forum_post.tagIds",
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
        this.allowedGuilds = parseGuildCsv(allowedGuilds);
        this.allowedTools = parseCsv(allowedTools, "DISCORD_MCP_ALLOWED_TOOLS");
        this.defaultGuildId = validateDefaultGuild(this.allowedGuilds, defaultGuildId);
    }

    static void validateConfiguration(String allowedGuilds, String allowedTools,
                                      String defaultGuildId) {
        Set<String> parsedGuilds = parseGuildCsv(allowedGuilds);
        parseCsv(allowedTools, "DISCORD_MCP_ALLOWED_TOOLS");
        validateDefaultGuild(parsedGuilds, defaultGuildId);
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
            System.err.println("Discord guild scope omitted tools by default: "
                    + omitted);
        }
        return ToolCallbackProvider.from(scoped);
    }

    private ToolCallback scopeIfResolvable(ToolCallback delegate, Set<String> omitted) {
        String toolName = delegate.getToolDefinition().name();
        if (EXPLICIT_ONLY_WHEN_GUILD_SCOPED.contains(toolName)
                && !allowedTools.contains(toolName)) {
            omitted.add(toolName);
            return null;
        }
        if (EXPLICIT_ONLY_WHEN_GUILD_SCOPED.contains(toolName)) {
            System.err.println("Discord guild scope is explicitly exporting " + toolName
                    + ", which can return a durable access credential");
        }
        SchemaProperties schema = schemaProperties(delegate.getToolDefinition());
        Set<String> declared = schema.names();
        if (!schema.structured().isEmpty()) {
            if (allowedTools.contains(toolName)) {
                throw startupError("Tool " + toolName
                        + " declares unreviewed structured arguments: " + schema.structured());
            }
            System.err.println("Discord guild scope omitted tool " + toolName
                    + " because its structured arguments need review: " + schema.structured());
            return null;
        }
        Set<String> unreviewedChannels = declared.stream()
                .filter(McpAccessPolicy::isGuildChannelArgument)
                .filter(field -> !REVIEWED_CHANNEL_ID_ARGUMENTS.contains(toolName + "." + field))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!unreviewedChannels.isEmpty()) {
            if (allowedTools.contains(toolName)) {
                throw startupError("Tool " + toolName
                        + " declares unreviewed Discord channel targets: "
                        + unreviewedChannels);
            }
            System.err.println("Discord guild scope omitted tool " + toolName
                    + " because its channel arguments need review: " + unreviewedChannels);
            return null;
        }
        Set<String> unreviewed = unreviewedIdArguments(toolName, declared);
        if (!unreviewed.isEmpty()) {
            if (allowedTools.contains(toolName)) {
                throw startupError("Tool " + toolName
                        + " declares unreviewed Discord ID targets: " + unreviewed);
            }
            System.err.println("Discord guild scope omitted tool " + toolName
                    + " because its ID arguments need review: " + unreviewed);
            return null;
        }
        boolean resolvable = declared.contains("guildId")
                || declared.stream().anyMatch(McpAccessPolicy::isGuildChannelArgument);
        if (resolvable) {
            return new GuildScopedToolCallback(delegate, declared);
        }
        if (allowedTools.contains(toolName)) {
            throw startupError("Tool " + toolName
                    + " has no guild-resolvable target and cannot be explicitly exported under "
                    + "DISCORD_MCP_ALLOWED_GUILDS");
        }
        omitted.add(toolName);
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
            if (guildIds.isEmpty()) {
                throw new IllegalArgumentException("A Discord guild or channel target is required");
            }
            if (!allowedGuilds.containsAll(guildIds)) {
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
                        // Jackson must decode a string token to report its length. The global read
                        // constraint still bounds that allocation before this target-specific cap.
                        if (valueToken == JsonToken.VALUE_STRING && parser.getTextLength() > 20) {
                            throw new IllegalArgumentException(field
                                    + " must be at most 20 characters");
                        }
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
        } catch (StreamConstraintsException error) {
            throw new IllegalArgumentException(
                    "Tool arguments exceed the maximum supported size", error);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Tool arguments are not valid JSON", error);
        }
    }

    private record TargetArgument(JsonToken token, String text) {
    }

    private record SchemaProperties(Set<String> names, Set<String> structured) {
    }

    private SchemaProperties schemaProperties(ToolDefinition definition) {
        try {
            JsonNode schema = objectMapper.readTree(definition.inputSchema());
            JsonNode properties = schema == null ? null : schema.get("properties");
            if (properties == null || properties.isNull()) {
                return new SchemaProperties(Set.of(), Set.of());
            }
            if (!properties.isObject()) {
                throw startupError("Tool " + definition.name()
                        + " has no object properties in its input schema");
            }
            Set<String> names = new LinkedHashSet<>();
            Set<String> structured = new LinkedHashSet<>();
            properties.propertyNames().forEach(name -> {
                names.add(name);
                JsonNode type = properties.get(name).get("type");
                if (type != null && ("object".equals(type.asText())
                        || "array".equals(type.asText()))) {
                    structured.add(name);
                }
            });
            return new SchemaProperties(Set.copyOf(names), Set.copyOf(structured));
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

    private static Set<String> unreviewedIdArguments(String toolName, Set<String> declared) {
        return declared.stream()
                .filter(McpAccessPolicy::isIdShapedArgument)
                .filter(field -> !"guildId".equals(field))
                .filter(field -> !isGuildChannelArgument(field))
                .filter(field -> !REVIEWED_NON_CHANNEL_ID_ARGUMENTS.contains(
                        toolName + "." + field))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static boolean isIdShapedArgument(String field) {
        String lowercase = field.toLowerCase(Locale.ROOT);
        // Lowercase words such as "valid" are not ID names. Without a separator or camel/upper
        // boundary, a lowercase suffix is ambiguous and must be covered by semantic schema review.
        return lowercase.equals("id") || lowercase.equals("ids")
                || lowercase.endsWith("_id") || lowercase.endsWith("_ids")
                || field.endsWith("Id") || field.endsWith("Ids")
                || field.endsWith("ID") || field.endsWith("IDs");
    }

    private static String requireSnowflakeText(TargetArgument value, String name) {
        if (value.token() != JsonToken.VALUE_STRING || !DiscordSnowflake.isValid(value.text())) {
            throw new IllegalArgumentException(name
                    + " must be a 17-20 digit Discord snowflake encoded as a JSON string");
        }
        return DiscordSnowflake.canonicalize(value.text());
    }

    private static Set<String> parseGuildCsv(String raw) {
        return parseCsv(raw, "DISCORD_MCP_ALLOWED_GUILDS").stream()
                .map(value -> {
                    requireSnowflake(value, "DISCORD_MCP_ALLOWED_GUILDS");
                    return DiscordSnowflake.canonicalize(value);
                })
                .collect(Collectors.toUnmodifiableSet());
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

    private static String validateDefaultGuild(Set<String> allowedGuilds, String defaultGuildId) {
        String normalizedDefaultGuildId = trimToNull(defaultGuildId);
        if (allowedGuilds.isEmpty()) {
            return normalizedDefaultGuildId;
        }
        if (normalizedDefaultGuildId != null
                && !normalizedDefaultGuildId.equals(defaultGuildId)) {
            throw startupError("DISCORD_GUILD_ID must not contain surrounding whitespace");
        }
        if (normalizedDefaultGuildId != null) {
            requireSnowflake(normalizedDefaultGuildId, "DISCORD_GUILD_ID");
            String canonicalDefaultGuildId = DiscordSnowflake.canonicalize(
                    normalizedDefaultGuildId);
            if (!allowedGuilds.contains(canonicalDefaultGuildId)) {
                throw startupError("DISCORD_GUILD_ID must be in DISCORD_MCP_ALLOWED_GUILDS");
            }
            return canonicalDefaultGuildId;
        }
        return null;
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
