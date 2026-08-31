package dev.saseq.configs;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class McpBearerAuthConfigTest {
    private static final String TOKEN = "12345678901234567890123456789012";

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
    void unsetTokenPreservesExistingDeploymentBehavior() throws Exception {
        var filter = new McpBearerAuthConfig.BearerFilter(null);
        var request = new MockHttpServletRequest("POST", "/mcp");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }
}
