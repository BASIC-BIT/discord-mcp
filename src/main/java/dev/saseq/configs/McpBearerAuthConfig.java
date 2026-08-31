package dev.saseq.configs;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;

/** Optional bearer authentication for the HTTP MCP endpoint. */
@Configuration
public class McpBearerAuthConfig {
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> mcpBearerAuthFilter(
            @Value("${DISCORD_MCP_ACCESS_TOKEN_FILE:}") String tokenFile) {
        String token = readToken(tokenFile);
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new BearerFilter(token));
        registration.addUrlPatterns("/mcp", "/mcp/*");
        registration.setName("mcpBearerAuthFilter");
        registration.setOrder(Integer.MIN_VALUE);
        return registration;
    }

    private static String readToken(String tokenFile) {
        if (tokenFile == null || tokenFile.isBlank()) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(Path.of(tokenFile), StandardCharsets.UTF_8);
            if (lines.size() != 1 || lines.get(0).trim().length() < 32) {
                throw new IllegalArgumentException(
                        "DISCORD_MCP_ACCESS_TOKEN_FILE must contain exactly one token of at least 32 characters");
            }
            return lines.get(0).trim();
        } catch (IOException error) {
            throw new IllegalArgumentException("Could not read DISCORD_MCP_ACCESS_TOKEN_FILE", error);
        }
    }

    static final class BearerFilter extends OncePerRequestFilter {
        private final byte[] expected;

        BearerFilter(String token) {
            this.expected = token == null ? null : ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            if (expected == null) {
                filterChain.doFilter(request, response);
                return;
            }
            String authorization = request.getHeader("Authorization");
            byte[] actual = authorization == null
                    ? new byte[0]
                    : authorization.getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(expected, actual)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setHeader("WWW-Authenticate", "Bearer");
                return;
            }
            filterChain.doFilter(request, response);
        }
    }
}
