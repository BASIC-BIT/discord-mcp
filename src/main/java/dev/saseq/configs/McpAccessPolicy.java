package dev.saseq.configs;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
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
    private static final String TARGET_ACCESS_DENIED =
            "Discord target is unavailable or outside the allowed guild scope";

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
        this.argumentObjectMapper = objectMapper.rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        this.allowedGuilds = parseCsv(allowedGuilds, "DISCORD_MCP_ALLOWED_GUILDS");
        this.allowedTools = parseCsv(allowedTools, "DISCORD_MCP_ALLOWED_TOOLS");
        this.defaultGuildId = trimToNull(defaultGuildId);

        this.allowedGuilds.forEach(id -> requireSnowflake(id, "DISCORD_MCP_ALLOWED_GUILDS"));
        if (!this.allowedGuilds.isEmpty() && this.defaultGuildId != null) {
            requireSnowflake(this.defaultGuildId, "DISCORD_GUILD_ID");
            if (!this.allowedGuilds.contains(this.defaultGuildId)) {
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

        ToolCallback[] selected = Arrays.stream(raw)
                .filter(callback -> allowedTools.isEmpty()
                        || allowedTools.contains(callback.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);

        if (allowedGuilds.isEmpty()) {
            return ToolCallbackProvider.from(selected);
        }

        ToolCallback[] scoped = Arrays.stream(selected)
                .map(this::scopeIfResolvable)
                .filter(callback -> callback != null)
                .toArray(ToolCallback[]::new);
        return ToolCallbackProvider.from(scoped);
    }

    private ToolCallback scopeIfResolvable(ToolCallback delegate) {
        Set<String> declared = schemaProperties(delegate.getToolDefinition());
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
            JsonNode parsed = parseArguments(arguments);
            Set<String> guildIds = resolveGuildIds(parsed, declaredArguments);
            if (guildIds.isEmpty() || !allowedGuilds.containsAll(guildIds)) {
                throw new SecurityException(TARGET_ACCESS_DENIED);
            }
        }
    }

    private Set<String> resolveGuildIds(JsonNode arguments, Set<String> declaredArguments) {
        Set<String> guildIds = new LinkedHashSet<>();
        if (declaredArguments.contains("guildId")) {
            JsonNode guildValue = arguments.get("guildId");
            boolean absent = guildValue == null || guildValue.isNull()
                    || (guildValue.isTextual() && guildValue.asText().isEmpty());
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
            JsonNode value = arguments.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            // Discord edit tools use an empty optional category ID to detach a channel.
            // It contributes no guild evidence; another declared target must still establish scope.
            if (value.isTextual() && value.asText().isEmpty()) {
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

    private JsonNode parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank() || "null".equals(arguments.strip())) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode parsed = argumentObjectMapper.readTree(arguments);
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalArgumentException("Tool arguments must be a JSON object");
            }
            return parsed;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Tool arguments are not valid JSON", error);
        }
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

    private static String requireSnowflakeText(JsonNode value, String name) {
        if (!value.isTextual() || !isSnowflake(value.asText())) {
            throw new IllegalArgumentException(name
                    + " must be a 17-20 digit Discord snowflake encoded as a JSON string");
        }
        return value.asText();
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
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void requireSnowflake(String value, String name) {
        if (!isSnowflake(value)) {
            throw startupError(name + " entries must be 17-20 digit Discord snowflakes");
        }
    }

    private static boolean isSnowflake(String value) {
        if (value == null || !value.matches("\\d{17,20}")) {
            return false;
        }
        return value.chars().anyMatch(character -> character != '0');
    }

    private static String normalizeAbsentArguments(String arguments) {
        return arguments == null || arguments.isBlank() || "null".equals(arguments.strip())
                ? "{}" : arguments;
    }

    private static IllegalArgumentException startupError(String message) {
        return new IllegalArgumentException(message);
    }
}
