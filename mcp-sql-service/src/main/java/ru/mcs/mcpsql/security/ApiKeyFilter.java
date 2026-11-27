package ru.mcs.mcpsql.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.mcs.mcpsql.config.MultiUserProperties;
import ru.mcs.mcpsql.config.ServiceProperties;
import ru.mcs.mcpsql.users.KeyService;
import ru.mcs.mcpsql.users.Principal;
import ru.mcs.mcpsql.users.QuotaException;
import ru.mcs.mcpsql.users.Scope;
import ru.mcs.mcpsql.users.UsageService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;

/**
 * /api/** needs a write key; /internal/** needs the internal key (multi-user mode only); everything else except
 * /health needs a read or a write key. Local mode compares with the two configured keys; multi-user mode looks
 * the key up in the user database and counts every POST to /mcp against the user's daily request limit.
 * The result is stored in the request as a {@link Principal}.
 */
@Component
@Order(10)
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL_ATTR = "mcpsql.principal";

    private static final Logger LOG = LoggerFactory.getLogger(ApiKeyFilter.class);

    private final byte[] readKey;
    private final byte[] writeKey;
    private final byte[] internalKey;
    private final boolean multi;
    private final KeyService keys;
    private final UsageService usage;

    public ApiKeyFilter(ServiceProperties properties, MultiUserProperties multiProperties, Optional<KeyService> keys) {
        this(properties, multiProperties, keys, Optional.empty());
    }

    @Autowired
    public ApiKeyFilter(ServiceProperties properties, MultiUserProperties multiProperties, Optional<KeyService> keys,
                        Optional<UsageService> usage) {
        this.readKey = properties.readKey().getBytes(StandardCharsets.UTF_8);
        this.writeKey = properties.writeKey().getBytes(StandardCharsets.UTF_8);
        this.internalKey = multiProperties.internalKey().getBytes(StandardCharsets.UTF_8);
        this.multi = multiProperties.enabled();
        this.keys = keys.orElse(null);
        this.usage = usage.orElse(null);
        if (multi && this.keys == null) {
            throw new IllegalStateException("multi-user mode needs the key service");
        }
        if (multi) {
            if (internalKey.length == 0) {
                LOG.warn("mcpsql.multi.internal-key is empty: /internal/** rejects every request");
            }
        } else if (readKey.length == 0 || writeKey.length == 0) {
            LOG.warn("mcpsql.read-key or mcpsql.write-key is empty: the matching endpoints reject every request");
        }
    }

    public static Principal principal(HttpServletRequest request) {
        return (Principal) request.getAttribute(PRINCIPAL_ATTR);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.equals("/health")) {
            chain.doFilter(request, response);
            return;
        }
        if (path.startsWith("/internal/")) {
            String header = request.getHeader("X-Internal-Key");
            byte[] given = header == null ? new byte[0] : header.strip().getBytes(StandardCharsets.UTF_8);
            if (!multi || !matches(given, internalKey)) {
                unauthorized(response);
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        Principal principal = authenticate(token(request));
        boolean needsWrite = path.startsWith("/api/");
        if (principal == null || (needsWrite && principal.scope() != Scope.WRITE)) {
            unauthorized(response);
            return;
        }
        request.setAttribute(PRINCIPAL_ATTR, principal);
        if (usage != null && "POST".equals(request.getMethod()) && path.startsWith("/mcp")) {
            try {
                usage.recordRequest(principal.userId(), Instant.now());
            } catch (QuotaException e) {
                tooManyRequests(response, e.getMessage());
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private Principal authenticate(String token) {
        if (multi) {
            return keys.authenticate(token, Instant.now()).orElse(null);
        }
        byte[] given = token.getBytes(StandardCharsets.UTF_8);
        if (matches(given, writeKey)) {
            return new Principal(0, Scope.WRITE, 0);
        }
        if (matches(given, readKey)) {
            return new Principal(0, Scope.READ, 0);
        }
        return null;
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("unauthorized");
    }

    private static void tooManyRequests(HttpServletResponse response, String message) throws IOException {
        response.setStatus(429);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(message);
    }

    private static String token(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).strip();
        }
        String apiKey = request.getHeader("X-API-Key");
        return apiKey == null ? "" : apiKey.strip();
    }

    private static boolean matches(byte[] token, byte[] key) {
        return key.length > 0 && MessageDigest.isEqual(token, key);
    }
}
