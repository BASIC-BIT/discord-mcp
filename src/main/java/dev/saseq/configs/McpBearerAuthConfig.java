package dev.saseq.configs;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/** Optional bearer authentication for the HTTP MCP endpoint. */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class McpBearerAuthConfig {
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> mcpBearerAuthFilter(
            @Value("${DISCORD_MCP_ACCESS_TOKEN_FILE:}") String tokenFile,
            @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String mcpEndpoint,
            @Value("${spring.ai.mcp.server.protocol:STREAMABLE}") String protocol) {
        String token = readToken(tokenFile);
        if (token != null && !"STREAMABLE".equalsIgnoreCase(protocol)) {
            throw startupError("Bearer authentication supports only the STREAMABLE HTTP protocol");
        }
        if (mcpEndpoint == null || !mcpEndpoint.startsWith("/") || mcpEndpoint.contains("*")) {
            throw startupError("MCP endpoint must be an absolute path without wildcards");
        }
        String endpoint = mcpEndpoint.endsWith("/") && mcpEndpoint.length() > 1
                ? mcpEndpoint.substring(0, mcpEndpoint.length() - 1)
                : mcpEndpoint;
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new BearerFilter(token));
        registration.addUrlPatterns(endpoint, endpoint + "/*");
        registration.setName("mcpBearerAuthFilter");
        registration.setOrder(Integer.MIN_VALUE);
        return registration;
    }

    private static String readToken(String tokenFile) {
        if (tokenFile == null || tokenFile.isBlank()) {
            return null;
        }
        try {
            String token = Files.readString(Path.of(tokenFile), StandardCharsets.UTF_8).strip();
            if (token.startsWith("\uFEFF")) {
                token = token.substring(1).strip();
            }
            if (token.length() < 32 || token.chars().anyMatch(Character::isWhitespace)) {
                throw startupError(
                        "DISCORD_MCP_ACCESS_TOKEN_FILE must contain exactly one token of at least 32 characters");
            }
            return token;
        } catch (IOException error) {
            throw startupError("Could not read DISCORD_MCP_ACCESS_TOKEN_FILE", error);
        }
    }

    private static IllegalArgumentException startupError(String message) {
        return startupError(message, null);
    }

    private static IllegalArgumentException startupError(String message, Throwable cause) {
        System.err.println("ERROR: Discord MCP bearer authentication is invalid: " + message);
        return cause == null ? new IllegalArgumentException(message) : new IllegalArgumentException(message, cause);
    }

    static final class BearerFilter extends OncePerRequestFilter {
        private final byte[] expected;

        BearerFilter(String token) {
            this.expected = token == null ? null : token.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            if (expected == null) {
                filterChain.doFilter(request, response);
                return;
            }
            String authorization = request.getHeader("Authorization");
            int separator = authorization == null ? -1 : authorization.indexOf(' ');
            boolean bearerScheme = separator > 0
                    && authorization.substring(0, separator).equalsIgnoreCase("Bearer");
            byte[] actual = bearerScheme
                    ? authorization.substring(separator + 1).getBytes(StandardCharsets.UTF_8)
                    : new byte[0];
            if (!MessageDigest.isEqual(expected, actual)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setHeader("WWW-Authenticate", "Bearer");
                return;
            }
            filterChain.doFilter(request, response);
        }
    }
}
