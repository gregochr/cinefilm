package gregochr.cinefilm.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CsrfHeaderFilterTest {

    private final CsrfHeaderFilter filter = new CsrfHeaderFilter();
    private final FilterChain chain = mock(FilterChain.class);

    private MockHttpServletResponse send(String method, String headerValue) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/anything");
        if (headerValue != null) {
            request.addHeader(CsrfHeaderFilter.HEADER, headerValue);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "OPTIONS", "TRACE"})
    void safeMethodsPassWithoutTheHeader(String method) throws Exception {
        MockHttpServletResponse response = send(method, null);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH"})
    void mutatingMethodsWithoutTheHeaderGet403AndNeverReachTheChain(String method) throws Exception {
        MockHttpServletResponse response = send(method, null);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"Missing X-CineFilm header\"}");
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aMutatingRequestWithTheHeaderSetToOnePasses() throws Exception {
        MockHttpServletResponse response = send("POST", "1");

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "true", "", "11", " 1"})
    void anyOtherHeaderValueIsRejected(String value) throws Exception {
        assertThat(send("DELETE", value).getStatus()).isEqualTo(403);
    }
}
