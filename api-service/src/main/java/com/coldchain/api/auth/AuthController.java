package com.coldchain.api.auth;

import com.coldchain.api.auth.RefreshTokenService.Rotation;
import com.coldchain.api.metrics.ApiMetrics;
import com.coldchain.api.metrics.ApiMetrics.LoginResult;
import com.coldchain.api.user.AppUser;
import com.coldchain.api.user.AppUserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login, token refresh and logout. None of these endpoints need an access token
 * ({@code @SecurityRequirements} with no value).
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
public class AuthController {

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    /**
     * @param accessToken             the JWT to send as {@code Authorization: Bearer <accessToken>}
     * @param expiresInSeconds        how long the access token is valid
     * @param refreshToken            single-use token for POST /api/auth/refresh (keep it secret)
     * @param refreshExpiresInSeconds how long the refresh token is valid
     */
    public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds,
                                String refreshToken, long refreshExpiresInSeconds,
                                String role, String tenantId) {
    }

    private final AuthenticationManager authenticationManager;
    private final AppUserRepository users;
    private final TokenService tokens;
    private final RefreshTokenService refreshTokens;
    private final JwtProperties jwtProperties;
    private final LoginRateLimiter rateLimiter;
    private final ApiMetrics metrics;

    public AuthController(AuthenticationManager authenticationManager, AppUserRepository users,
                          TokenService tokens, RefreshTokenService refreshTokens, JwtProperties jwtProperties,
                          LoginRateLimiter rateLimiter, ApiMetrics metrics) {
        this.authenticationManager = authenticationManager;
        this.users = users;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.jwtProperties = jwtProperties;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
    }

    @PostMapping("/login")
    @Operation(summary = "Log in: get an access token (JWT) and a refresh token")
    @ApiResponse(responseCode = "401", description = "Wrong username or password")
    @ApiResponse(responseCode = "429", description = "Too many failed attempts; see the Retry-After header")
    @SecurityRequirements
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String ip = httpRequest.getRemoteAddr();
        // Checked BEFORE the password: once locked, even the right password is refused, otherwise
        // guessing could simply continue.
        try {
            rateLimiter.checkAllowed(ip, request.username());
        } catch (TooManyRequestsException e) {
            metrics.login(LoginResult.BLOCKED);
            throw e;
        }
        try {
            // Throws an AuthenticationException (-> 401) for a wrong username or password.
            authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
        } catch (AuthenticationException e) {
            rateLimiter.recordFailure(ip, request.username());
            metrics.login(LoginResult.FAILURE);
            throw e;
        }
        rateLimiter.recordSuccess(ip, request.username());
        metrics.login(LoginResult.SUCCESS);

        AppUser user = users.findByUsername(request.username())
                .orElseThrow(() -> new BadCredentialsException("Unknown user"));
        return tokensFor(user, refreshTokens.issueForNewSession(user));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for a new access token AND a new refresh token "
            + "(the old one stops working; using it again ends the session)")
    @ApiResponse(responseCode = "401", description = "Unknown, expired, revoked or reused refresh token")
    @ApiResponse(responseCode = "429", description = "Too many failed attempts; see the Retry-After header")
    @SecurityRequirements
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest httpRequest) {
        String ip = httpRequest.getRemoteAddr();
        rateLimiter.checkAllowed(ip, null);
        Rotation rotation;
        try {
            rotation = refreshTokens.rotate(request.refreshToken());
        } catch (InvalidRefreshTokenException e) {
            rateLimiter.recordFailure(ip, null);
            throw e;
        }
        return tokensFor(rotation.user(), rotation.nextRefreshToken());
    }

    @PostMapping("/logout")
    @Operation(summary = "End the session of this refresh token (the access token expires on its own)")
    @ApiResponse(responseCode = "204", description = "Session ended")
    @SecurityRequirements
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        refreshTokens.revokeSession(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /** Every refresh token, new or rotated, lives exactly {@code refresh-expiry} from now. */
    private TokenResponse tokensFor(AppUser user, String refreshToken) {
        return new TokenResponse(tokens.issueToken(user), "Bearer", jwtProperties.expiry().toSeconds(),
                refreshToken, jwtProperties.refreshExpiry().toSeconds(),
                user.getRole().name(), user.getTenantId());
    }
}
