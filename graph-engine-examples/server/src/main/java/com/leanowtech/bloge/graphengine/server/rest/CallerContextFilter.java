package com.leanowtech.bloge.graphengine.server.rest;

import com.leanowtech.bloge.graphengine.service.CallerContext;
import com.leanowtech.bloge.graphengine.service.CallerContextHolder;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Servlet filter that populates the {@link CallerContextHolder} for each HTTP
 * request so the graph-engine service can enforce RBAC policies.
 *
 * <h3>Resolution strategy (in priority order)</h3>
 * <ol>
 *   <li><b>Spring Security</b> — when the SecurityContext contains an
 *       authenticated principal, the filter extracts role names from
 *       {@code GrantedAuthority.getAuthority()}.  Authorities prefixed with
 *       {@code ROLE_} have the prefix stripped so they match the plain role
 *       names used in {@link com.leanowtech.bloge.graphengine.model.RbacPolicy}.</li>
 *   <li><b>Request header</b> — as a fallback (for development or gateway
 *       pre-auth), the filter reads comma-separated role names from the
 *       {@code X-Graph-Engine-Roles} header.</li>
 *   <li><b>Anonymous</b> — when neither source yields roles, the filter binds
 *       {@link CallerContext#ANONYMOUS} so the service enforces RBAC as an
 *       unauthenticated caller.</li>
 * </ol>
 *
 * <p>The holder is always cleared in a {@code finally} block to prevent
 * thread-local leaks.</p>
 */
public class CallerContextFilter implements Filter {

    /** Default request header used as a fallback role source. */
    public static final String ROLES_HEADER = "X-Graph-Engine-Roles";
    private static final String ROLE_PREFIX = "ROLE_";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            CallerContextHolder.set(resolve((HttpServletRequest) request));
            chain.doFilter(request, response);
        } finally {
            CallerContextHolder.clear();
        }
    }

    private CallerContext resolve(HttpServletRequest request) {
        Set<String> roles = resolveFromSecurityContext();
        if (roles != null && !roles.isEmpty()) {
            return new CallerContext(roles);
        }
        String headerValue = request.getHeader(ROLES_HEADER);
        if (headerValue != null && !headerValue.isBlank()) {
            Set<String> headerRoles = new LinkedHashSet<>();
            Arrays.stream(headerValue.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(headerRoles::add);
            return new CallerContext(headerRoles);
        }
        return CallerContext.ANONYMOUS;
    }

    @SuppressWarnings("unchecked")
    private Set<String> resolveFromSecurityContext() {
        try {
            Class<?> holderType = Class.forName("org.springframework.security.core.context.SecurityContextHolder");
            Object securityContext = holderType.getMethod("getContext").invoke(null);
            if (securityContext == null) {
                return null;
            }
            Object authentication = securityContext.getClass().getMethod("getAuthentication").invoke(securityContext);
            if (authentication == null) {
                return null;
            }
            Object isAuthenticated = authentication.getClass().getMethod("isAuthenticated").invoke(authentication);
            if (!Boolean.TRUE.equals(isAuthenticated)) {
                return null;
            }
            Collection<?> authorities = (Collection<?>) authentication.getClass()
                    .getMethod("getAuthorities").invoke(authentication);
            if (authorities == null || authorities.isEmpty()) {
                return null;
            }
            Set<String> roles = new LinkedHashSet<>();
            Method getAuthority = null;
            for (Object authority : authorities) {
                if (getAuthority == null) {
                    getAuthority = authority.getClass().getMethod("getAuthority");
                }
                String name = (String) getAuthority.invoke(authority);
                if (name != null) {
                    roles.add(name.startsWith(ROLE_PREFIX) ? name.substring(ROLE_PREFIX.length()) : name);
                }
            }
            return roles;
        } catch (ClassNotFoundException ignored) {
            // Spring Security not on classpath
            return null;
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }
}
