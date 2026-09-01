package dev.saseq.configs;

import dev.saseq.services.LocalFileGuard;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.DigestOutputStream;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
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
    private static final Logger LOGGER = LoggerFactory.getLogger(McpToolPolicy.class);
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
    private static final Set<String> GLOBAL_TARGET_TOOLS = Set.of(
            "delete_webhook", "send_webhook_message", "delete_invite", "get_invite_details",
            "send_private_message", "edit_private_message", "delete_private_message",
            "read_private_messages"
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
    private final byte[] auditHashSalt;

    public McpToolPolicy(
            JDA jda,
            ObjectMapper objectMapper,
            @Value("${DISCORD_MCP_ALLOWED_GUILDS:}") String allowedGuilds,
            @Value("${DISCORD_MCP_ALLOWED_TOOLS:}") String allowedTools,
            @Value("${DISCORD_GUILD_ID:}") String defaultGuildId,
            @Value("${DISCORD_MCP_WRITE_MODE:allow}") String writeMode,
            @Value("${DISCORD_MCP_AUDIT_FILE:}") String auditFile,
            @Value("${DISCORD_MCP_AUDIT_MAX_BYTES:10485760}") String auditMaxBytes,
            @Value("${DISCORD_MCP_FILE_ROOT:}") String fileRoot,
            @Value("${DISCORD_MCP_DOWNLOAD_ROOT:}") String downloadRoot) {
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
        this.auditHashSalt = this.auditFile == null ? null : randomAuditHashSalt();
        if (this.auditFile != null && this.auditMaxBytes < 4096) {
            throw startupError("DISCORD_MCP_AUDIT_MAX_BYTES must be at least 4096");
        }

        if (this.auditFile != null) {
            // Refuse the ordinary containment case before creating the parent or probe file.
            // The resolved check below still catches aliases and symlinks once the file exists.
            requireLexicalAuditIsolation(fileRoot, "DISCORD_MCP_FILE_ROOT");
            requireLexicalAuditIsolation(downloadRoot, "DISCORD_MCP_DOWNLOAD_ROOT");
            if (this.auditFile.getParent() != null) {
                try {
                    Files.createDirectories(this.auditFile.getParent());
                } catch (IOException error) {
                    throw startupError("DISCORD_MCP_AUDIT_FILE parent cannot be created");
                }
            }
            try {
                try (var ignored = Files.newByteChannel(this.auditFile,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND)) {
                    // Open-and-close probes the exact runtime append permission without altering data.
                }
            } catch (IOException error) {
                throw startupError("DISCORD_MCP_AUDIT_FILE is not appendable");
            }
            requireResolvedAuditIsolation(fileRoot, "DISCORD_MCP_FILE_ROOT");
            requireResolvedAuditIsolation(downloadRoot, "DISCORD_MCP_DOWNLOAD_ROOT");
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
        Set<String> explicitlyAllowedGlobalTargets = new LinkedHashSet<>(allowedTools);
        explicitlyAllowedGlobalTargets.retainAll(GLOBAL_TARGET_TOOLS);
        if (!allowedGuilds.isEmpty() && !explicitlyAllowedGlobalTargets.isEmpty()) {
            throw startupError("DISCORD_MCP_ALLOWED_TOOLS contains tools that cannot prove guild "
                    + "scope: " + explicitlyAllowedGlobalTargets);
        }
        ToolCallback[] filtered = Arrays.stream(raw)
                .filter(callback -> allowedTools.isEmpty()
                        || allowedTools.contains(callback.getToolDefinition().name()))
                .filter(callback -> allowedGuilds.isEmpty()
                        || !GLOBAL_TARGET_TOOLS.contains(callback.getToolDefinition().name()))
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
            String argumentsForDelegate = isAbsentArgumentMap(arguments) ? "{}" : arguments;
            if (!policyActive && auditFile == null) {
                return toolContext == null
                        ? delegate.call(argumentsForDelegate)
                        : delegate.call(argumentsForDelegate, toolContext);
            }
            // Hash the raw caller representation so the audit can correlate the original request.
            // Policy-active calls inspect the parsed tree but preserve the caller's representation
            // after target types and guild scope pass.
            String argumentsForHash = arguments == null || arguments.isBlank() ? "{}" : arguments;
            String argumentsSaltedSha256 = auditFile == null
                    ? null : sha256(argumentsForHash, auditHashSalt);
            // Audit-only deployments preserve upstream argument handling and avoid materializing
            // supported large payloads a second time. They intentionally record no caller-supplied
            // target IDs because no active policy schema has established that those fields belong
            // to the tool.
            JsonNode parsed = objectMapper.createObjectNode();
            if (policyActive) {
                try {
                    parsed = parseArguments(arguments);
                } catch (IllegalArgumentException error) {
                    auditBestEffort(tool, "denied-invalid-arguments", invocationId,
                            Set.of(), null,
                            argumentsSaltedSha256, error.getClass().getSimpleName());
                    throw error;
                }
                try {
                    rejectUndeclaredArguments(tool, parsed, declaredArguments);
                } catch (SecurityException error) {
                    auditBestEffort(tool, "denied-undeclared-argument", invocationId,
                            Set.of(), null, argumentsSaltedSha256,
                            error.getClass().getSimpleName());
                    throw error;
                }
            }
            Set<String> guildIds;
            try {
                guildIds = resolveGuildIds(parsed, declaredArguments, !allowedGuilds.isEmpty());
            } catch (SecurityException error) {
                auditBestEffort(tool, "denied-invalid-target", invocationId, Set.of(), parsed,
                        argumentsSaltedSha256, null);
                throw new SecurityException(TARGET_ACCESS_DENIED);
            }
            enforceGuilds(tool, invocationId, guildIds, parsed, argumentsSaltedSha256);

            if (writeMode == WriteMode.PREVIEW && !READ_ONLY_TOOLS.contains(tool)) {
                audit(tool, "preview", invocationId, guildIds, parsed,
                        argumentsSaltedSha256, null);
                return "WRITE_PREVIEW: This deployment runs in preview mode; " + tool
                        + " was not called, and retrying here will produce the same result. Arguments: "
                        + previewArguments(argumentsForHash, parsed);
            }

            audit(tool, "started", invocationId, guildIds, parsed,
                    argumentsSaltedSha256, null);
            try {
                String result = toolContext == null
                        ? delegate.call(argumentsForDelegate)
                        : delegate.call(argumentsForDelegate, toolContext);
                String completionWarning = auditBestEffort(
                        tool, "tool-returned", invocationId, guildIds, parsed,
                        argumentsSaltedSha256, null);
                return completionWarning == null
                        ? result : result + System.lineSeparator() + completionWarning;
            } catch (RuntimeException error) {
                auditBestEffort(tool, "failed", invocationId, guildIds, parsed,
                        argumentsSaltedSha256, error.getClass().getSimpleName());
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

    private static boolean isAbsentArgumentMap(String arguments) {
        return arguments == null || arguments.isBlank() || "null".equals(arguments.strip());
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
            } catch (RuntimeException ignored) {
                // Malformed or otherwise rejected identifiers are unresolved targets and must
                // follow the same audited denial path without exposing a JDA exception message.
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

    private void enforceGuilds(String tool, String invocationId, Set<String> guildIds,
                               JsonNode arguments, String argumentsSaltedSha256) {
        if (allowedGuilds.isEmpty()) {
            return;
        }
        if (guildIds.isEmpty()) {
            auditBestEffort(tool, "denied-unresolved-guild", invocationId, guildIds, arguments,
                    argumentsSaltedSha256, null);
            throw new SecurityException(TARGET_ACCESS_DENIED);
        }
        Set<String> denied = new LinkedHashSet<>(guildIds);
        denied.removeAll(allowedGuilds);
        if (!denied.isEmpty()) {
            auditBestEffort(tool, "denied-guild", invocationId, guildIds, arguments,
                    argumentsSaltedSha256, null);
            throw new SecurityException(TARGET_ACCESS_DENIED);
        }
    }

    // Expected operator traffic is low. Serializing the two append operations keeps each JSONL
    // record and size-based rotation atomic without retaining a file handle across rotations.
    private synchronized void audit(String tool, String outcome, String invocationId,
                                    Set<String> guildIds, JsonNode arguments,
                                    String argumentsSaltedSha256, String errorType) {
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
            if (arguments != null && policyActive) {
                var argumentIds = objectMapper.createObjectNode();
                arguments.properties().forEach(entry -> {
                    if (entry.getKey().matches("(?i).*id$")
                            && entry.getValue().isValueNode() && !entry.getValue().isNull()) {
                        argumentIds.put(entry.getKey(),
                                boundedAuditIdentifier(entry.getValue().asText()));
                    }
                });
                if (!argumentIds.isEmpty()) {
                    event.set("argumentIds", argumentIds);
                }
            }
            if (argumentsSaltedSha256 != null) {
                event.put("argumentsSaltedSha256", argumentsSaltedSha256);
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
                                   String argumentsSaltedSha256, String errorType) {
        if (auditFile == null && outcome.startsWith("denied-")) {
            LOGGER.warn("Discord MCP policy denied tool {} ({}).", tool, outcome);
            return null;
        }
        try {
            audit(tool, outcome, invocationId, guildIds, arguments,
                    argumentsSaltedSha256, errorType);
            return null;
        } catch (RuntimeException auditError) {
            String warning = "WARNING: The tool outcome is preserved, but its audit completion record failed.";
            LOGGER.warn("{} {}", warning, auditError.getMessage());
            return warning;
        }
    }

    private void rotateAuditIfNeeded(int nextLineBytes) throws IOException {
        Path rotated = auditFile.resolveSibling(auditFile.getFileName() + ".1");
        if (Files.exists(rotated) && Files.size(rotated) > auditMaxBytes) {
            Files.delete(rotated);
        }
        if (!Files.exists(auditFile)) {
            return;
        }
        long activeBytes = Files.size(auditFile);
        if (activeBytes > auditMaxBytes) {
            Files.delete(auditFile);
            return;
        }
        if (activeBytes + nextLineBytes <= auditMaxBytes) {
            return;
        }
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
        boolean idShaped = normalized.endsWith("id");
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
            List<String> firstKeys = supplied.stream().limit(5)
                    .map(McpToolPolicy::boundedDiagnosticName)
                    .toList();
            String remainder = supplied.size() > firstKeys.size() ? ", additional keys omitted" : "";
            throw new SecurityException("Tool " + tool + " received " + supplied.size()
                    + " undeclared arguments; first key names: " + firstKeys + remainder);
        }
    }

    private String previewArguments(String raw, JsonNode parsed) {
        final int maximumCharacters = 16_384;
        final int largeValueCharacters = 4_096;
        var redacted = (ObjectNode) parsed.deepCopy();
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
        int prefixLength = maximumCharacters - suffix.length();
        if (prefixLength > 0 && Character.isHighSurrogate(compact.charAt(prefixLength - 1))) {
            prefixLength--;
        }
        return compact.substring(0, prefixLength) + suffix;
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

    private String boundedAuditIdentifier(String value) {
        final int maximumCharacters = 64;
        if (value.length() <= maximumCharacters) {
            return value;
        }
        return "<omitted " + value.length() + " characters; saltedSha256="
                + sha256(value, auditHashSalt) + ">";
    }

    private static String boundedDiagnosticName(String value) {
        final int maximumCharacters = 64;
        String sanitized = value.codePoints()
                .map(codePoint -> Character.isISOControl(codePoint) ? '?' : codePoint)
                .limit(maximumCharacters)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        return value.codePointCount(0, value.length()) > maximumCharacters
                ? sanitized + "..." : sanitized;
    }

    private void requireLexicalAuditIsolation(String configuredRoot, String variableName) {
        if (configuredRoot == null || configuredRoot.isBlank()) {
            return;
        }
        Path configuredRootPath;
        try {
            configuredRootPath = Path.of(configuredRoot).toAbsolutePath().normalize();
        } catch (InvalidPathException unusableRoot) {
            return;
        }
        if (auditFile.startsWith(configuredRootPath)) {
            throw startupError("DISCORD_MCP_AUDIT_FILE must be outside " + variableName);
        }
    }

    private void requireResolvedAuditIsolation(String configuredRoot, String variableName) {
        if (configuredRoot == null || configuredRoot.isBlank()) {
            return;
        }
        LocalFileGuard.Root resolvedRoot;
        try {
            resolvedRoot = LocalFileGuard.resolveRoot(configuredRoot, variableName);
        } catch (IllegalArgumentException unusableRoot) {
            return;
        }
        try {
            Path realAuditFile = auditFile.toRealPath();
            if (realAuditFile.startsWith(resolvedRoot.path())) {
                throw startupError("DISCORD_MCP_AUDIT_FILE must be outside " + variableName);
            }
        } catch (IOException error) {
            throw startupError("DISCORD_MCP_AUDIT_FILE cannot be resolved after its append check");
        }
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

    static Set<String> globalTargetToolNames() {
        return GLOBAL_TARGET_TOOLS;
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
        return sha256(value, null);
    }

    private static String sha256(String value, byte[] prefix) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (prefix != null) {
                digest.update(prefix);
            }
            try (Writer writer = new OutputStreamWriter(
                    new DigestOutputStream(OutputStream.nullOutputStream(), digest),
                    StandardCharsets.UTF_8)) {
                writer.write(value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException | IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] randomAuditHashSalt() {
        byte[] salt = new byte[32];
        new SecureRandom().nextBytes(salt);
        return salt;
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
