package dev.saseq.configs;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

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
        var filter = new McpBearerAuthConfig.BearerFilter(null);
        var request = new MockHttpServletRequest("POST", "/mcp");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void unsetTokenDoesNotValidateBearerOnlyEndpointSettings() {
        var registration = new McpBearerAuthConfig()
                .mcpBearerAuthFilter("", "relative-*", "");

        assertThat(registration.getUrlPatterns()).containsExactly("/*");
    }

    @Test
    void configuredEndpointAndTrailingNewlineTokenAreAccepted() throws Exception {
        Path tokenFile = tempDir.resolve("token");
        Files.writeString(tokenFile, TOKEN + System.lineSeparator() + System.lineSeparator());

        var registration = new McpBearerAuthConfig()
                .mcpBearerAuthFilter(tokenFile.toString(), "/custom-mcp/", "STREAMABLE");

        assertThat(registration.getUrlPatterns()).containsExactly("/*");
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

        assertThatThrownBy(() -> new McpBearerAuthConfig()
                .mcpBearerAuthFilter(tokenFile.toString(), "/actuator/health", "STREAMABLE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("public health endpoint");
    }

    @Test
    void configuredBearerRequiresExplicitStreamableProtocol() throws Exception {
        Path tokenFile = tempDir.resolve("token");
        Files.writeString(tokenFile, TOKEN);

        assertThatThrownBy(() -> new McpBearerAuthConfig()
                .mcpBearerAuthFilter(tokenFile.toString(), "/mcp", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("STREAMABLE");
    }
}
