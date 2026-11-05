package io.github.zzzzain777.retouch.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/** 会话令牌：HS256 JWT，sub 为用户 id，放在 httpOnly Cookie {@code session} 里（与上游一致）。 */
@Component
public class SessionTokens {

    public static final String COOKIE = "session";

    private final AuthProperties props;
    private final NimbusJwtEncoder encoder;
    private final JwtDecoder decoder;

    public SessionTokens(AuthProperties props) {
        byte[] secret = props.jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("retouch.auth.jwt-secret 至少需要 32 字节");
        }
        SecretKey key = new SecretKeySpec(secret, "HmacSHA256");
        this.props = props;
        this.encoder = NimbusJwtEncoder.withSecretKey(key).build();
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    public String issue(UUID userId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(props.ttl()))
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    /** 任何无效情形（签名、过期、格式）都返回空，由调用方统一处理为未登录。 */
    public Optional<UUID> read(String token) {
        try {
            return Optional.of(UUID.fromString(decoder.decode(token).getSubject()));
        } catch (JwtException | IllegalArgumentException | NullPointerException exc) {
            return Optional.empty();
        }
    }

    public ResponseCookie cookieFor(UUID userId) {
        return ResponseCookie.from(COOKIE, issue(userId))
                .httpOnly(true)
                .secure(props.secureCookie())
                .sameSite("Lax")
                .path("/")
                .maxAge(props.ttl())
                .build();
    }

    public ResponseCookie clearedCookie() {
        return ResponseCookie.from(COOKIE, "").httpOnly(true).secure(props.secureCookie()).sameSite("Lax")
                .path("/").maxAge(0).build();
    }
}
