package io.github.zzzzain777.retouch.auth;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.zzzzain777.retouch.common.ApiException;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    record Credentials(
            @NotNull @Size(min = 3, max = 32) @Pattern(regexp = "\\s*[\\p{L}\\p{N}_]+\\s*",
                    message = "用户名只能包含字母、数字和下划线") String username,
            @NotNull @Size(min = 6, max = 64) String password) {

        String normalizedUsername() {
            return username.strip();
        }
    }

    record UserOut(UUID id, String username) {
    }

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final SessionTokens tokens;

    AuthController(UserRepository users, PasswordEncoder passwords, SessionTokens tokens) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
    }

    @PostMapping("/register")
    @Transactional
    ResponseEntity<UserOut> register(@Valid @RequestBody Credentials credentials) {
        String username = credentials.normalizedUsername();
        if (users.existsByUsername(username)) {
            throw ApiException.conflict("该用户名已被占用");
        }
        User user;
        try {
            user = users.saveAndFlush(new User(UUID.randomUUID(), username, passwords.encode(credentials.password())));
        } catch (DataIntegrityViolationException race) {
            throw ApiException.conflict("该用户名已被占用");
        }
        return withSession(HttpStatus.CREATED, user);
    }

    @PostMapping("/login")
    ResponseEntity<UserOut> login(@Valid @RequestBody Credentials credentials) {
        User user = users.findByUsername(credentials.normalizedUsername())
                .filter(found -> passwords.matches(credentials.password(), found.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "用户名或密码错误"));
        return withSession(HttpStatus.OK, user);
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, tokens.clearedCookie().toString()).build();
    }

    @GetMapping("/me")
    UserOut me(CurrentUser user) {
        return new UserOut(user.id(), user.username());
    }

    private ResponseEntity<UserOut> withSession(HttpStatus status, User user) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, tokens.cookieFor(user.getId()).toString())
                .body(new UserOut(user.getId(), user.getUsername()));
    }
}
