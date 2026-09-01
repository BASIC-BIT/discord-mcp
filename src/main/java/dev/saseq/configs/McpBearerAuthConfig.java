package dev.saseq.configs;

import dev.saseq.services.SensitiveFileGuard;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;

/** Optional bearer authentication for the HTTP MCP endpoint. */
@Configuration
public class McpBearerAuthConfig {
    private static final int MAX_TOKEN_FILE_BYTES = 4_096;

    @Bean
    BearerAuthSettings mcpBearerAuthSettings(
            @Value("${DISCORD_MCP_ACCESS_TOKEN_FILE:}") String tokenFile,
            @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String mcpEndpoint,
            @Value("${spring.ai.mcp.server.protocol:}") String protocol,
            @Value("${spring.main.web-application-type:}") String webApplicationType,
            @Value("${management.server.port:}") String managementServerPort,
            @Value("${DISCORD_MCP_FILE_ROOT:}") String fileRoot,
            @Value("${DISCORD_MCP_DOWNLOAD_ROOT:}") String downloadRoot,
            @Value("${DISCORD_MCP_AUDIT_FILE:}") String auditFile,
            @Value("${logging.file.name:}") String operationalLogFile) {
        requireCredentialIsolation(tokenFile, fileRoot, "DISCORD_MCP_FILE_ROOT");
        requireCredentialIsolation(tokenFile, downloadRoot, "DISCORD_MCP_DOWNLOAD_ROOT");
        requireCredentialAuditIsolation(tokenFile, auditFile);
        requireCredentialOperationalLogIsolation(tokenFile, operationalLogFile);
        requireSingleLinkCredential(tokenFile);
        String token = readToken(tokenFile);
        if (token != null && !"STREAMABLE".equalsIgnoreCase(protocol)) {
            throw startupError("Bearer authentication supports only the STREAMABLE HTTP protocol; "
                    + "set SPRING_PROFILES_ACTIVE=http");
        }
        if (token != null && !"servlet".equalsIgnoreCase(webApplicationType)) {
            throw startupError("Bearer authentication requires servlet web application mode; "
                    + "set SPRING_PROFILES_ACTIVE=http");
        }
        if (token != null && managementServerPort != null && !managementServerPort.isBlank()) {
            throw startupError("Bearer authentication requires management.server.port to remain unset; "
                    + "a separate management servlet context is outside the MCP bearer filter");
        }
        if (token != null) {
            String normalizedEndpoint = normalizeEndpoint(mcpEndpoint);
            if ("/actuator/health".equals(normalizedEndpoint)) {
                throw startupError("MCP endpoint must not equal the public health endpoint");
            }
        }
        return new BearerAuthSettings(token == null ? null : sha256(token));
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    FilterRegistrationBean<OncePerRequestFilter> mcpBearerAuthFilter(BearerAuthSettings settings) {
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new BearerFilter(settings.tokenDigest()));
        registration.addUrlPatterns("/*");
        registration.setName("mcpBearerAuthFilter");
        registration.setOrder(Integer.MIN_VALUE);
        return registration;
    }

    record BearerAuthSettings(byte[] tokenDigest) {
        BearerAuthSettings {
            tokenDigest = tokenDigest == null ? null : tokenDigest.clone();
        }

        @Override
        public byte[] tokenDigest() {
            return tokenDigest == null ? null : tokenDigest.clone();
        }

        @Override
        public String toString() {
            return "BearerAuthSettings[tokenDigest="
                    + (tokenDigest == null ? "disabled" : "configured") + "]";
        }
    }

    private static String readToken(String tokenFile) {
        if (tokenFile == null || tokenFile.isBlank()) {
            return null;
        }
        try {
            byte[] bytes;
            try (InputStream input = Files.newInputStream(Path.of(tokenFile))) {
                bytes = input.readNBytes(MAX_TOKEN_FILE_BYTES + 1);
            }
            if (bytes.length > MAX_TOKEN_FILE_BYTES) {
                throw startupError("DISCORD_MCP_ACCESS_TOKEN_FILE exceeds 4096 bytes");
            }
            String token = new String(bytes, StandardCharsets.UTF_8).strip();
            if (token.startsWith("\uFEFF")) {
                token = token.substring(1).strip();
            }
            if (token.length() < 32 || token.chars().anyMatch(Character::isWhitespace)) {
                throw startupError(
                        "DISCORD_MCP_ACCESS_TOKEN_FILE must contain exactly one token of at least 32 characters");
            }
            if (token.codePoints().anyMatch(codePoint -> codePoint < 0x21 || codePoint > 0x7e)) {
                throw startupError(
                        "DISCORD_MCP_ACCESS_TOKEN_FILE must use printable ASCII characters valid in an HTTP header");
            }
            return token;
        } catch (IOException | InvalidPathException error) {
            throw startupError("Could not read DISCORD_MCP_ACCESS_TOKEN_FILE", error);
        }
    }

    private static void requireCredentialIsolation(String configuredTokenFile,
                                                   String configuredRoot, String variableName) {
        if (configuredTokenFile == null || configuredTokenFile.isBlank()
                || configuredRoot == null || configuredRoot.isBlank()) {
            return;
        }
        Path tokenPath;
        try {
            tokenPath = Path.of(configuredTokenFile).toAbsolutePath().normalize();
        } catch (InvalidPathException unusablePath) {
            return;
        }
        SensitiveFileGuard.requireOutsideRoot(tokenPath, configuredRoot,
                "DISCORD_MCP_ACCESS_TOKEN_FILE", variableName);
    }

    private static void requireCredentialAuditIsolation(String configuredTokenFile,
                                                         String configuredAuditFile) {
        if (configuredTokenFile == null || configuredTokenFile.isBlank()
                || configuredAuditFile == null || configuredAuditFile.isBlank()) {
            return;
        }
        try {
            Path tokenPath = Path.of(configuredTokenFile).toAbsolutePath().normalize();
            Path auditPath = Path.of(configuredAuditFile.strip()).toAbsolutePath().normalize();
            Path auditName = auditPath.getFileName();
            if (auditName == null) {
                return;
            }
            Path rotatedAuditPath = auditPath.resolveSibling(auditName + ".1");
            if (sameConfiguredFile(tokenPath, auditPath)
                    || sameConfiguredFile(tokenPath, rotatedAuditPath)) {
                throw startupError("DISCORD_MCP_ACCESS_TOKEN_FILE must differ from "
                        + "DISCORD_MCP_AUDIT_FILE and its rotation file");
            }
        } catch (InvalidPathException unusablePath) {
            // The dedicated readers report an invalid setting with its owning variable name.
        }
    }

    private static void requireCredentialOperationalLogIsolation(String configuredTokenFile,
                                                                  String configuredLogFile) {
        if (configuredTokenFile == null || configuredTokenFile.isBlank()
                || configuredLogFile == null || configuredLogFile.isBlank()) {
            return;
        }
        try {
            Path tokenPath = Path.of(configuredTokenFile).toAbsolutePath().normalize();
            Path logPath = Path.of(configuredLogFile.strip()).toAbsolutePath().normalize();
            if (sameConfiguredFile(tokenPath, logPath)) {
                throw startupError("DISCORD_MCP_ACCESS_TOKEN_FILE must differ from logging.file.name");
            }
        } catch (InvalidPathException unusablePath) {
            // The owning token or logging configuration reports its own invalid path.
        }
    }

    private static void requireSingleLinkCredential(String configuredTokenFile) {
        if (configuredTokenFile == null || configuredTokenFile.isBlank()) {
            return;
        }
        Path tokenPath;
        try {
            tokenPath = Path.of(configuredTokenFile).toAbsolutePath().normalize();
        } catch (InvalidPathException unusableToken) {
            // readToken reports the owning setting and fails startup below.
            return;
        }
        try {
            SensitiveFileGuard.requireExclusiveRegularFile(tokenPath, true);
        } catch (IOException error) {
            throw startupError("DISCORD_MCP_ACCESS_TOKEN_FILE " + error.getMessage(), error);
        }
    }

    private static boolean sameConfiguredFile(Path left, Path right) {
        if (left.equals(right)) {
            return true;
        }
        try {
            if (Files.exists(left) && Files.exists(right) && Files.isSameFile(left, right)) {
                return true;
            }
            Path leftParent = left.getParent();
            Path rightParent = right.getParent();
            return leftParent != null && rightParent != null
                    && leftParent.toRealPath().equals(rightParent.toRealPath())
                    && left.getFileName().equals(right.getFileName());
        } catch (IOException ignored) {
            return false;
        }
    }

    private static IllegalArgumentException startupError(String message) {
        return startupError(message, null);
    }

    private static IllegalArgumentException startupError(String message, Throwable cause) {
        System.err.println("ERROR: Discord MCP bearer authentication is invalid: " + message);
        return cause == null ? new IllegalArgumentException(message) : new IllegalArgumentException(message, cause);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String normalizeEndpoint(String endpoint) {
        if (endpoint == null || !endpoint.startsWith("/") || endpoint.contains("*")) {
            throw startupError("MCP endpoint must be an absolute path without wildcards");
        }
        return endpoint.endsWith("/") && endpoint.length() > 1
                ? endpoint.substring(0, endpoint.length() - 1)
                : endpoint;
    }

    static final class BearerFilter extends OncePerRequestFilter {
        private final byte[] expected;

        BearerFilter(byte[] expected) {
            this.expected = expected == null ? null : expected.clone();
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            if (expected == null || isExactPublicHealthRequest(request)) {
                filterChain.doFilter(request, response);
                return;
            }
            String authorization = request.getHeader("Authorization");
            int separator = authorization == null ? -1 : authorization.indexOf(' ');
            boolean bearerScheme = separator > 0
                    && authorization.substring(0, separator).equalsIgnoreCase("Bearer");
            byte[] actual = sha256(bearerScheme ? authorization.substring(separator + 1) : "");
            if (!MessageDigest.isEqual(expected, actual)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setHeader("WWW-Authenticate", "Bearer");
                return;
            }
            filterChain.doFilter(request, response);
        }

        private static boolean isExactPublicHealthRequest(HttpServletRequest request) {
            String expectedRawUri = request.getContextPath() + "/actuator/health";
            return "GET".equalsIgnoreCase(request.getMethod())
                    && "/actuator/health".equals(request.getServletPath())
                    && (request.getPathInfo() == null || request.getPathInfo().isEmpty())
                    && expectedRawUri.equals(request.getRequestURI());
        }

    }
}
