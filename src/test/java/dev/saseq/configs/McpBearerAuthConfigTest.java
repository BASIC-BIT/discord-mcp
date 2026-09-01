package dev.saseq.configs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpBearerAuthConfigTest {
    private static final String TOKEN = "12345678901234567890123456789012";

    @TempDir
    Path tempDir;

    @Test
    void missingTokenIsRejected() throws Exception {
        var filter = new McpBearerAuthConfig.BearerFilter(TOKEN);
        var request = new MockHttpServletRequest("POST", "/mcp");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void wrongLengthBearerTokenIsRejected() throws Exception {
        var filter = new McpBearerAuthConfig.BearerFilter(TOKEN);
        var request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer short");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void exactBearerTokenPasses() throws Exception {
        var filter = new McpBearerAuthConfig.BearerFilter(TOKEN);
        var request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void bearerSchemeIsCaseInsensitive() throws Exception {
        var filter = new McpBearerAuthConfig.BearerFilter(TOKEN);
        var request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "bearer " + TOKEN);
        var chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void unsetTokenPreservesExistingDeploymentBehavior() throws Exception {
        var filter = new McpBearerAuthConfig.BearerFilter((String) null);
        var request = new MockHttpServletRequest("POST", "/mcp");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void unsetTokenDoesNotValidateBearerOnlyEndpointSettings() {
        var config = new McpBearerAuthConfig();
        var settings = config.mcpBearerAuthSettings(
                "", "relative-*", "", "", "9090", "", "", "", "");
        var registration = config.mcpBearerAuthFilter(settings);

        assertThat(registration.getUrlPatterns()).containsExactly("/*");
    }

    @Test
    void configuredEndpointAndTrailingNewlineTokenAreAccepted() throws Exception {
        Path tokenFile = tempDir.resolve("token");
        Files.writeString(tokenFile, TOKEN + System.lineSeparator() + System.lineSeparator());

        var config = new McpBearerAuthConfig();
        var settings = settings(
                tokenFile.toString(), "/custom-mcp/", "STREAMABLE", "servlet");
        var registration = config.mcpBearerAuthFilter(settings);

        assertThat(registration.getUrlPatterns()).containsExactly("/*");
    }

    @Test
    void oversizedTokenFileFailsBeforeItIsReadInFull() throws Exception {
        Path tokenFile = tempDir.resolve("oversized-token");
        Files.writeString(tokenFile, "x".repeat(4_097));

        assertThatThrownBy(() -> settings(tokenFile.toString(), "/mcp", "STREAMABLE", "servlet"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds 4096 bytes");
    }

    @Test
    void headerInvalidBearerCharactersFailAtStartup() throws Exception {
        for (String invalidCharacter : new String[]{"\u0000", "\u007f", "é"}) {
            Path tokenFile = tempDir.resolve("invalid-" + (int) invalidCharacter.charAt(0));
            Files.writeString(tokenFile, TOKEN + invalidCharacter);

            assertThatThrownBy(() -> settings(
                    tokenFile.toString(), "/mcp", "STREAMABLE", "servlet"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("printable ASCII");
        }
    }

    @Test
    void healthRemainsPublicWhileOtherFuturePathsDefaultToProtected() throws Exception {
        var filter = new McpBearerAuthConfig.BearerFilter(TOKEN);
        var healthRequest = new MockHttpServletRequest("GET", "/actuator/health");
        healthRequest.setServletPath("/actuator/health");
        var healthChain = new MockFilterChain();

        filter.doFilter(healthRequest, new MockHttpServletResponse(), healthChain);

        assertThat(healthChain.getRequest()).isSameAs(healthRequest);

        var futureRequest = new MockHttpServletRequest("GET", "/future-endpoint");
        var futureResponse = new MockHttpServletResponse();
        var futureChain = new MockFilterChain();

        filter.doFilter(futureRequest, futureResponse, futureChain);

        assertThat(futureResponse.getStatus()).isEqualTo(401);
        assertThat(futureChain.getRequest()).isNull();
    }

    @Test
    void healthTraversalAndSubpathsRemainProtected() throws Exception {
        var filter = new McpBearerAuthConfig.BearerFilter(TOKEN);
        var traversalRequest = new MockHttpServletRequest("GET", "/actuator/health/../mcp");
        traversalRequest.setServletPath("/mcp");
        var traversalResponse = new MockHttpServletResponse();
        var traversalChain = new MockFilterChain();

        filter.doFilter(traversalRequest, traversalResponse, traversalChain);

        assertThat(traversalResponse.getStatus()).isEqualTo(401);
        assertThat(traversalChain.getRequest()).isNull();

        var subpathRequest = new MockHttpServletRequest("GET", "/actuator/health/liveness");
        subpathRequest.setServletPath("/actuator/health/liveness");
        var subpathResponse = new MockHttpServletResponse();
        var subpathChain = new MockFilterChain();

        filter.doFilter(subpathRequest, subpathResponse, subpathChain);

        assertThat(subpathResponse.getStatus()).isEqualTo(401);
        assertThat(subpathChain.getRequest()).isNull();

        var postRequest = new MockHttpServletRequest("POST", "/actuator/health");
        postRequest.setServletPath("/actuator/health");
        var postResponse = new MockHttpServletResponse();
        var postChain = new MockFilterChain();

        filter.doFilter(postRequest, postResponse, postChain);

        assertThat(postResponse.getStatus()).isEqualTo(401);
        assertThat(postChain.getRequest()).isNull();
    }

    @Test
    void mcpEndpointCannotCollideWithPublicHealthEndpoint() throws Exception {
        Path tokenFile = tempDir.resolve("token");
        Files.writeString(tokenFile, TOKEN);

        assertThatThrownBy(() -> settings(
                tokenFile.toString(), "/actuator/health", "STREAMABLE", "servlet"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("public health endpoint");
    }

    @Test
    void configuredBearerRequiresExplicitStreamableProtocol() throws Exception {
        Path tokenFile = tempDir.resolve("token");
        Files.writeString(tokenFile, TOKEN);

        assertThatThrownBy(() -> settings(tokenFile.toString(), "/mcp", "", "none"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("STREAMABLE");
    }

    @Test
    void configuredBearerRequiresServletWebApplication() throws Exception {
        Path tokenFile = tempDir.resolve("token");
        Files.writeString(tokenFile, TOKEN);

        assertThatThrownBy(() -> settings(tokenFile.toString(), "/mcp", "STREAMABLE", "none"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("servlet");
    }

    @Test
    void configuredBearerIsValidatedBeforeServletFilterCreation() throws Exception {
        Path tokenFile = tempDir.resolve("token");
        Files.writeString(tokenFile, TOKEN);

        var settings = settings(tokenFile.toString(), "/mcp", "STREAMABLE", "servlet");

        assertThat(settings.tokenDigest()).hasSize(32);
        assertThat(settings.toString()).doesNotContain(TOKEN).contains("configured");
    }

    @Test
    void configuredBearerRejectsASeparateManagementPort() throws Exception {
        Path tokenFile = tempDir.resolve("token");
        Files.writeString(tokenFile, TOKEN);

        assertThatThrownBy(() -> new McpBearerAuthConfig().mcpBearerAuthSettings(
                tokenFile.toString(), "/mcp", "STREAMABLE", "servlet", "9090",
                "", "", "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("management.server.port");
    }

    @Test
    void bearerCredentialMustRemainOutsideToolFileRoots() throws Exception {
        Path uploads = Files.createDirectories(tempDir.resolve("uploads"));
        Path tokenFile = uploads.resolve("access-token");
        Files.writeString(tokenFile, TOKEN);

        assertThatThrownBy(() -> settings(
                tokenFile.toString(), "/mcp", "STREAMABLE", "servlet",
                uploads.toString(), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside DISCORD_MCP_FILE_ROOT");

        assertThatThrownBy(() -> settings(
                tokenFile.toString(), "/mcp", "STREAMABLE", "servlet",
                "", uploads.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside DISCORD_MCP_DOWNLOAD_ROOT");
    }

    @Test
    void bearerCredentialMustNotHaveAdditionalHardLinksWhenLinkCountsAreAvailable() throws Exception {
        Assumptions.assumeTrue(
                tempDir.getFileSystem().supportedFileAttributeViews().contains("unix"),
                "unix:nlink is unavailable on this filesystem");
        Path uploads = Files.createDirectories(tempDir.resolve("uploads"));
        Path tokenFile = tempDir.resolve("access-token");
        Files.writeString(tokenFile, TOKEN);
        try {
            Files.createLink(uploads.resolve("token-alias"), tokenFile);
        } catch (UnsupportedOperationException | SecurityException | java.io.IOException error) {
            Assumptions.abort("hard links are unavailable on this filesystem");
        }

        assertThatThrownBy(() -> settings(
                tokenFile.toString(), "/mcp", "STREAMABLE", "servlet",
                uploads.toString(), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("additional hard links");
    }

    @Test
    void bearerCredentialMustResolveToARegularFile() throws Exception {
        Assumptions.assumeTrue(
                tempDir.getFileSystem().supportedFileAttributeViews().contains("unix"),
                "Unix special files are unavailable on this filesystem");
        Path fifo = tempDir.resolve("access-token-fifo");
        Process process;
        try {
            process = new ProcessBuilder("mkfifo", fifo.toString()).start();
        } catch (UnsupportedOperationException | SecurityException | java.io.IOException error) {
            Assumptions.abort("mkfifo is unavailable on this system");
            return;
        }
        Assumptions.assumeTrue(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0,
                "mkfifo could not create a test FIFO");

        assertThatThrownBy(() -> settings(
                fifo.toString(), "/mcp", "STREAMABLE", "servlet"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must resolve to a regular file");
    }

    @Test
    void bearerCredentialMustDifferFromAuditFiles() throws Exception {
        Path audit = tempDir.resolve("audit.jsonl");
        Files.writeString(audit, TOKEN);

        assertThatThrownBy(() -> settings(
                audit.toString(), "/mcp", "STREAMABLE", "servlet",
                "", "", "  " + audit + "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ from DISCORD_MCP_AUDIT_FILE");

        Path rotatedAudit = tempDir.resolve("second-audit.jsonl.1");
        Files.writeString(rotatedAudit, TOKEN);
        assertThatThrownBy(() -> settings(
                rotatedAudit.toString(), "/mcp", "STREAMABLE", "servlet",
                "", "", tempDir.resolve("second-audit.jsonl").toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ from DISCORD_MCP_AUDIT_FILE");
    }

    @Test
    void bearerCredentialMustDifferFromTheOperationalLog() throws Exception {
        Path logFile = tempDir.resolve("server.log");
        Files.writeString(logFile, TOKEN);

        assertThatThrownBy(() -> new McpBearerAuthConfig().mcpBearerAuthSettings(
                logFile.toString(), "/mcp", "STREAMABLE", "servlet", "",
                "", "", "", logFile.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ from logging.file.name");
    }

    private static McpBearerAuthConfig.BearerAuthSettings settings(
            String tokenFile, String mcpEndpoint, String protocol, String webApplicationType) {
        return settings(tokenFile, mcpEndpoint, protocol, webApplicationType, "", "", "");
    }

    private static McpBearerAuthConfig.BearerAuthSettings settings(
            String tokenFile, String mcpEndpoint, String protocol, String webApplicationType,
            String fileRoot, String downloadRoot) {
        return settings(tokenFile, mcpEndpoint, protocol, webApplicationType,
                fileRoot, downloadRoot, "");
    }

    private static McpBearerAuthConfig.BearerAuthSettings settings(
            String tokenFile, String mcpEndpoint, String protocol, String webApplicationType,
            String fileRoot, String downloadRoot, String auditFile) {
        return new McpBearerAuthConfig().mcpBearerAuthSettings(
                tokenFile, mcpEndpoint, protocol, webApplicationType, "",
                fileRoot, downloadRoot, auditFile, "");
    }
}
