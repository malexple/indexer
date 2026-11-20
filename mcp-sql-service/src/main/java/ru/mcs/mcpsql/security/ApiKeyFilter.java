package ru.mcs.mcpsql.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.mcs.mcpsql.config.ServiceProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** /api/** needs the write key; everything else except /health needs the read or the write key. */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(ApiKeyFilter.class);

    private final byte[] readKey;
    private final byte[] writeKey;

    public ApiKeyFilter(ServiceProperties properties) {
        this.readKey = properties.readKey().getBytes(StandardCharsets.UTF_8);
        this.writeKey = properties.writeKey().getBytes(StandardCharsets.UTF_8);
        if (readKey.length == 0 || writeKey.length == 0) {
            LOG.warn("mcpsql.read-key or mcpsql.write-key is empty: the matching endpoints reject every request");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.equals("/health")) {
            chain.doFilter(request, response);
            return;
        }
        byte[] token = token(request);
        boolean allowed = path.startsWith("/api/")
                ? matches(token, writeKey)
                : matches(token, readKey) || matches(token, writeKey);
        if (!allowed) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "Bearer");
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("unauthorized");
            return;
        }
        chain.doFilter(request, response);
    }

    private static byte[] token(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).strip().getBytes(StandardCharsets.UTF_8);
        }
        String apiKey = request.getHeader("X-API-Key");
        return apiKey == null ? new byte[0] : apiKey.strip().getBytes(StandardCharsets.UTF_8);
    }

    private static boolean matches(byte[] token, byte[] key) {
        return key.length > 0 && MessageDigest.isEqual(token, key);
    }
}