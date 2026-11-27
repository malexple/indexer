package ru.mcs.mcpsql.site;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityHeadersFilterTest {

    @Test
    void everyAnswerCarriesTheSecurityHeaders() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SecurityHeadersFilter().doFilter(new MockHttpServletRequest("GET", "/"), response, (rq, rs) -> { });

        assertThat(response.getHeader("Content-Security-Policy")).contains("default-src 'self'", "frame-ancestors 'none'");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    }
}
