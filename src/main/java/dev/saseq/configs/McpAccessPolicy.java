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
    static final int MAX_ORDINARY_ARGUMENT_STRING_CHARACTERS = 16_384;
    static final long MAX_ORDINARY_ARGUMENT_DOCUMENT_CHARACTERS = 1_000_000L;
    private static final String TARGET_ACCESS_DENIED =
            "Discord target is not in the bot's channel cache (archived threads and forum posts "
                    + "may be absent) or is outside the allowed guild scope";
    private static final Set<String> EXPLICIT_ONLY_WHEN_GUILD_SCOPED = Set.of(
            "create_invite", "list_invites", "create_webhook", "list_webhooks");
    private static final Set<String> SCALAR_SCHEMA_TYPES = Set.of(
            "string", "number", "integer", "boolean");
    private static final Set<String> LARGE_ARGUMENT_TOOLS = Set.of(
            "create_emoji", "send_file", "set_guild_scheduled_event_image");
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
            "list_webhooks.channelId",
            "modify_forum_post.postId",
            "move_channel.categoryId", "move_channel.channelId",
            "move_member.channelId",
            "read_messages.channelId",
            "remove_reaction.channelId",
            "send_file.channelId",
            "send_message.channelId",
            "upsert_member_channel_permissions.channelId",
            "upsert_role_channel_permissions.channelId");
    // Pin every reviewed argument name, including aliases without an ID suffix. This makes any
    // future schema expansion fail closed instead of depending on a reviewer recognizing that a
    // new inviteCode, webhookUrl, filePath, or similar scalar carries cross-guild authority.
    private static final Map<String, Set<String>> REVIEWED_ARGUMENTS = Map.ofEntries(
            Map.entry("add_reaction", Set.of("channelId", "messageId", "emoji")),
            Map.entry("assign_role", Set.of("guildId", "userId", "roleId")),
            Map.entry("ban_member", Set.of(
                    "guildId", "userId", "deleteMessageSeconds", "reason")),
            Map.entry("create_category", Set.of("guildId", "name")),
            Map.entry("create_emoji", Set.of(
                    "guildId", "name", "image", "imageUrl", "roles")),
            Map.entry("create_forum_channel", Set.of(
                    "guildId", "name", "categoryId", "topic", "nsfw", "slowmode",
                    "position")),
            Map.entry("create_forum_post", Set.of(
                    "guildId", "channelId", "title", "message", "tagIds")),
            Map.entry("create_guild_scheduled_event", Set.of(
                    "guildId", "name", "description", "scheduledStartTime", "scheduledEndTime",
                    "entityType", "channelId", "location", "recurrenceRule")),
            Map.entry("create_invite", Set.of(
                    "guildId", "channelId", "maxAge", "maxUses", "temporary", "unique")),
            Map.entry("create_role", Set.of(
                    "guildId", "name", "color", "hoist", "mentionable", "permissions")),
            Map.entry("create_stage_channel", Set.of(
                    "guildId", "name", "categoryId", "bitrate")),
            Map.entry("create_text_channel", Set.of(
                    "guildId", "name", "categoryId", "topic", "nsfw", "slowmode",
                    "position")),
            Map.entry("create_voice_channel", Set.of(
                    "guildId", "name", "categoryId", "userLimit", "bitrate")),
            Map.entry("create_webhook", Set.of("channelId", "name")),
            Map.entry("delete_category", Set.of("guildId", "categoryId")),
            Map.entry("delete_channel", Set.of("guildId", "channelId", "reason")),
            Map.entry("delete_channel_permission_overwrite", Set.of(
                    "guildId", "channelId", "targetType", "targetId", "reason")),
            Map.entry("delete_emoji", Set.of("guildId", "emojiId")),
            Map.entry("delete_guild_scheduled_event", Set.of("guildId", "eventId")),
            Map.entry("delete_invite", Set.of("inviteCode")),
            Map.entry("delete_message", Set.of("channelId", "messageId")),
            Map.entry("delete_private_message", Set.of("userId", "messageId")),
            Map.entry("delete_role", Set.of("guildId", "roleId")),
            Map.entry("delete_webhook", Set.of("webhookId")),
            Map.entry("disconnect_member", Set.of("guildId", "userId")),
            Map.entry("download_attachment", Set.of(
                    "channelId", "messageId", "attachmentId")),
            Map.entry("edit_category", Set.of(
                    "guildId", "categoryId", "name", "position", "reason")),
            Map.entry("edit_emoji", Set.of("guildId", "emojiId", "name", "roles")),
            Map.entry("edit_forum_channel", Set.of(
                    "guildId", "channelId", "name", "topic", "nsfw", "slowmode",
                    "categoryId", "position", "defaultSort", "defaultLayout", "reason")),
            Map.entry("edit_guild_scheduled_event", Set.of(
                    "guildId", "eventId", "status", "name", "description",
                    "scheduledStartTime", "scheduledEndTime", "location", "recurrenceRule")),
            Map.entry("edit_message", Set.of("channelId", "messageId", "newMessage")),
            Map.entry("edit_private_message", Set.of("userId", "messageId", "newMessage")),
            Map.entry("edit_role", Set.of(
                    "guildId", "roleId", "name", "color", "hoist", "mentionable",
                    "permissions")),
            Map.entry("edit_text_channel", Set.of(
                    "guildId", "channelId", "name", "topic", "nsfw", "slowmode",
                    "categoryId", "position", "reason")),
            Map.entry("edit_voice_channel", Set.of(
                    "channelId", "name", "bitrate", "userLimit", "rtcRegion")),
            Map.entry("find_category", Set.of("guildId", "categoryName")),
            Map.entry("find_channel", Set.of("guildId", "channelName")),
            Map.entry("get_attachment", Set.of("channelId", "messageId", "attachmentId")),
            Map.entry("get_bans", Set.of("guildId", "limit")),
            Map.entry("get_bot_info", Set.of("guildId")),
            Map.entry("get_channel_info", Set.of("guildId", "channelId")),
            Map.entry("get_emoji_details", Set.of("guildId", "emojiId")),
            Map.entry("get_forum_channel_info", Set.of("guildId", "channelId")),
            Map.entry("get_guild_scheduled_event_users", Set.of(
                    "guildId", "eventId", "limit", "withMember")),
            Map.entry("get_invite_details", Set.of("inviteCode", "withCounts")),
            Map.entry("get_member_by_id", Set.of("userId", "guildId")),
            Map.entry("get_message", Set.of("channelId", "messageId")),
            Map.entry("get_server_info", Set.of("guildId")),
            Map.entry("get_user_id_by_name", Set.of("username", "guildId")),
            Map.entry("kick_member", Set.of("guildId", "userId", "reason")),
            Map.entry("list_active_threads", Set.of("guildId")),
            Map.entry("list_channel_permission_overwrites", Set.of("guildId", "channelId")),
            Map.entry("list_channels", Set.of("guildId")),
            Map.entry("list_channels_in_category", Set.of("guildId", "categoryId")),
            Map.entry("list_emojis", Set.of("guildId")),
            Map.entry("list_forum_channels", Set.of("guildId")),
            Map.entry("list_forum_posts", Set.of("guildId", "channelId")),
            Map.entry("list_forum_tags", Set.of("guildId", "channelId")),
            Map.entry("list_guild_scheduled_events", Set.of("guildId", "withUserCount")),
            Map.entry("list_invites", Set.of("guildId")),
            Map.entry("list_roles", Set.of("guildId")),
            Map.entry("list_webhooks", Set.of("channelId")),
            Map.entry("modify_forum_post", Set.of(
                    "guildId", "postId", "locked", "archived", "pinned", "tagIds", "reason")),
            Map.entry("modify_voice_state", Set.of("guildId", "userId", "mute", "deafen")),
            Map.entry("move_channel", Set.of(
                    "guildId", "channelId", "categoryId", "position", "reason")),
            Map.entry("move_member", Set.of("guildId", "userId", "channelId")),
            Map.entry("read_messages", Set.of("channelId", "count", "before", "after", "around")),
            Map.entry("read_private_messages", Set.of(
                    "userId", "count", "before", "after", "around")),
            Map.entry("remove_reaction", Set.of("channelId", "messageId", "emoji")),
            Map.entry("remove_role", Set.of("guildId", "userId", "roleId")),
            Map.entry("remove_timeout", Set.of("guildId", "userId", "reason")),
            Map.entry("search_members", Set.of("query", "count", "guildId")),
            Map.entry("send_file", Set.of(
                    "channelId", "filePath", "fileUrl", "fileData", "fileName", "message")),
            Map.entry("send_message", Set.of("channelId", "message")),
            Map.entry("send_private_message", Set.of("userId", "message")),
            Map.entry("send_webhook_message", Set.of("webhookUrl", "message")),
            Map.entry("set_guild_scheduled_event_image", Set.of(
                    "guildId", "eventId", "imageUrl", "filePath")),
            Map.entry("set_nickname", Set.of("guildId", "userId", "nick", "reason")),
            Map.entry("timeout_member", Set.of(
                    "guildId", "userId", "durationSeconds", "reason")),
            Map.entry("unban_member", Set.of("guildId", "userId", "reason")),
            Map.entry("upsert_member_channel_permissions", Set.of(
                    "guildId", "channelId", "userId", "allowRaw", "denyRaw",
                    "allowPermissions", "denyPermissions", "reason")),
            Map.entry("upsert_role_channel_permissions", Set.of(
                    "guildId", "channelId", "roleId", "allowRaw", "denyRaw",
                    "allowPermissions", "denyPermissions", "reason")));

    private final JDA jda;
    private final ObjectMapper objectMapper;
    private final ObjectMapper argumentObjectMapper;
    private final ObjectMapper ordinaryArgumentObjectMapper;
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
        this.ordinaryArgumentObjectMapper = createOrdinaryArgumentObjectMapper(objectMapper);
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
        return createArgumentObjectMapper(objectMapper, MAX_ARGUMENT_STRING_CHARACTERS,
                MAX_ARGUMENT_DOCUMENT_CHARACTERS);
    }

    static ObjectMapper createOrdinaryArgumentObjectMapper(ObjectMapper objectMapper) {
        return createArgumentObjectMapper(objectMapper, MAX_ORDINARY_ARGUMENT_STRING_CHARACTERS,
                MAX_ORDINARY_ARGUMENT_DOCUMENT_CHARACTERS);
    }

    private static ObjectMapper createArgumentObjectMapper(ObjectMapper objectMapper,
                                                            int maxStringLength,
                                                            long maxDocumentLength) {
        var argumentFactory = objectMapper.tokenStreamFactory().rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxStringLength(maxStringLength)
                        .maxDocumentLength(maxDocumentLength)
                        .build())
                .build();
        return new ObjectMapper(argumentFactory);
    }

    static Set<String> reviewedToolNames() {
        return REVIEWED_ARGUMENTS.keySet();
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
        SchemaProperties schema;
        try {
            schema = schemaProperties(delegate.getToolDefinition());
        } catch (IllegalArgumentException error) {
            if (allowedTools.contains(toolName)) {
                throw startupError(error.getMessage());
            }
            System.err.println("Discord guild scope omitted tool " + toolName
                    + " because its input schema is invalid or unsupported");
            return null;
        }
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
        Set<String> unreviewed = unreviewedArguments(toolName, declared);
        if (!unreviewed.isEmpty()) {
            if (allowedTools.contains(toolName)) {
                throw startupError("Tool " + toolName
                        + " declares unreviewed arguments: " + unreviewed);
            }
            System.err.println("Discord guild scope omitted tool " + toolName
                    + " because its arguments need review: " + unreviewed);
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
        private final Set<String> policyArguments;
        private final ObjectMapper policyArgumentObjectMapper;

        private GuildScopedToolCallback(ToolCallback delegate, Set<String> declaredArguments) {
            this.delegate = delegate;
            this.declaredArguments = declaredArguments;
            Set<String> selected = declaredArguments.stream()
                    .filter(field -> "guildId".equals(field) || isGuildChannelArgument(field))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if ("create_invite".equals(delegate.getToolDefinition().name())) {
                selected.add("maxAge");
                selected.add("maxUses");
            }
            this.policyArguments = Set.copyOf(selected);
            this.policyArgumentObjectMapper = LARGE_ARGUMENT_TOOLS.contains(
                    delegate.getToolDefinition().name())
                    ? argumentObjectMapper : ordinaryArgumentObjectMapper;
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
            Map<String, TargetArgument> parsed = parsePolicyArguments(
                    arguments, policyArguments, policyArgumentObjectMapper);
            Set<String> guildIds = resolveGuildIds(parsed, declaredArguments);
            if (guildIds.isEmpty()) {
                throw new IllegalArgumentException("A Discord guild or channel target is required");
            }
            if (!allowedGuilds.containsAll(guildIds)) {
                throw new SecurityException(TARGET_ACCESS_DENIED);
            }
            if ("create_invite".equals(delegate.getToolDefinition().name())) {
                requirePositiveInviteBound(parsed.get("maxAge"), "maxAge");
                requirePositiveInviteBound(parsed.get("maxUses"), "maxUses");
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

    private Map<String, TargetArgument> parsePolicyArguments(
            String arguments, Set<String> policyArguments, ObjectMapper parserObjectMapper) {
        if (arguments == null || arguments.isBlank() || "null".equals(arguments.strip())) {
            return Map.of();
        }
        try {
            // Upload-capable tools keep the streaming path so their documented payloads are not
            // copied into a tree. Ordinary tools have no large-string contract, so force a
            // bounded validation pass before decoding any target value.
            if (parserObjectMapper == ordinaryArgumentObjectMapper) {
                JsonNode validated = parserObjectMapper.readTree(arguments);
                if (validated == null || !validated.isObject()) {
                    throw new IllegalArgumentException("Tool arguments must be a JSON object");
                }
            }
            Map<String, TargetArgument> parsed = new LinkedHashMap<>();
            try (JsonParser parser = parserObjectMapper.tokenStreamFactory()
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
                    if (policyArguments.contains(field)) {
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

    private static void requirePositiveInviteBound(TargetArgument value, String name) {
        if (value == null || value.token() != JsonToken.VALUE_STRING
                || value.text() == null || value.text().isEmpty()) {
            throw new IllegalArgumentException(name
                    + " must be explicitly set to a positive integer for a guild-scoped invite");
        }
        try {
            if (Integer.parseInt(value.text()) <= 0) {
                throw new IllegalArgumentException(name
                        + " must be a positive integer for a guild-scoped invite");
            }
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(name
                    + " must be a positive integer for a guild-scoped invite", error);
        }
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
                throw new IllegalArgumentException("Tool " + definition.name()
                        + " has no object properties in its input schema");
            }
            Set<String> names = new LinkedHashSet<>();
            Set<String> structured = new LinkedHashSet<>();
            properties.propertyNames().forEach(name -> {
                names.add(name);
                JsonNode property = properties.get(name);
                JsonNode type = property == null ? null : property.get("type");
                if (type == null || !type.isTextual()
                        || !SCALAR_SCHEMA_TYPES.contains(type.asText())) {
                    structured.add(name);
                }
            });
            return new SchemaProperties(Set.copyOf(names), Set.copyOf(structured));
        } catch (RuntimeException error) {
            if (error instanceof IllegalArgumentException) {
                throw error;
            }
            throw new IllegalArgumentException(
                    "Tool " + definition.name() + " has an invalid input schema", error);
        }
    }

    static boolean isGuildChannelArgument(String field) {
        String normalized = field.toLowerCase(Locale.ROOT);
        return normalized.endsWith("id") && (normalized.contains("channel")
                || normalized.contains("category") || normalized.contains("forum")
                || normalized.contains("thread") || normalized.contains("post"));
    }

    private static Set<String> unreviewedArguments(String toolName, Set<String> declared) {
        Set<String> reviewed = REVIEWED_ARGUMENTS.getOrDefault(toolName, Set.of());
        return declared.stream()
                .filter(field -> !reviewed.contains(field))
                .collect(Collectors.toCollection(LinkedHashSet::new));
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
                    requireSnowflake(value, "DISCORD_MCP_ALLOWED_GUILDS entries");
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
            throw startupError(name + " must be 17-20 digit Discord snowflakes");
        }
    }

    private static String validateDefaultGuild(Set<String> allowedGuilds, String defaultGuildId) {
        String normalizedDefaultGuildId = trimToNull(defaultGuildId);
        if (allowedGuilds.isEmpty()) {
            return normalizedDefaultGuildId;
        }
        if (defaultGuildId != null && !defaultGuildId.isEmpty()
                && !defaultGuildId.strip().equals(defaultGuildId)) {
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
