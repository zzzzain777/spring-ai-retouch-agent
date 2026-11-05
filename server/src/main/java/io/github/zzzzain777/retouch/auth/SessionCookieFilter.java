package io.github.zzzzain777.retouch.auth;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 从 Cookie 读会话令牌并确认用户仍存在。认证失败不在这里报错，交给安全配置对受保护路径统一返回 401。
 */
class SessionCookieFilter extends OncePerRequestFilter {

    private final SessionTokens tokens;
    private final UserRepository users;

    SessionCookieFilter(SessionTokens tokens, UserRepository users) {
        this.tokens = tokens;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (SessionTokens.COOKIE.equals(cookie.getName())) {
                    tokens.read(cookie.getValue())
                            .flatMap(users::findById)
                            .ifPresent(user -> SecurityContextHolder.getContext().setAuthentication(
                                    UsernamePasswordAuthenticationToken.authenticated(
                                            new CurrentUser(user.getId(), user.getUsername()), null, List.of())));
                }
            }
        }
        chain.doFilter(request, response);
    }
}
