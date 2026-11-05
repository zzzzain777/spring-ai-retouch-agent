package io.github.zzzzain777.retouch.auth;

import java.nio.charset.StandardCharsets;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * 无状态认证：只认 Cookie 里的会话令牌。注册、登录、健康检查公开，其余 /api 与 /events 都需要登录。
 * 未登录统一返回 401 {@code {"detail": "未登录或会话已过期"}}。
 */
@Configuration
class SecurityConfig {

    private static final byte[] UNAUTHENTICATED = "{\"detail\":\"未登录或会话已过期\"}".getBytes(StandardCharsets.UTF_8);

    @Bean
    SecurityFilterChain api(HttpSecurity http, SessionTokens tokens, UserRepository users) throws Exception {
        http
                // 与上游一致：同站 Cookie（SameSite=Lax）+ JSON 接口，不使用 CSRF 令牌
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new SessionCookieFilter(tokens, users), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login", "/api/auth/logout")
                        .permitAll()
                        .requestMatchers("/api/health", "/actuator/health", "/actuator/info", "/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exc) -> {
                    response.setStatus(401);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getOutputStream().write(UNAUTHENTICATED);
                }));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
