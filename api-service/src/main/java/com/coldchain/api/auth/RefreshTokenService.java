package com.coldchain.api.auth;

import com.coldchain.api.user.AppUser;
import com.coldchain.api.user.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Refresh tokens with rotation and reuse detection:
 * <ul>
 *     <li>login creates a token in a new family (one family = one login session);</li>
 *     <li>each refresh revokes the presented token and issues a new one in the same family;</li>
 *     <li>presenting an already revoked token means it was copied (only one party can hold the
 *         current token): the whole family is revoked, so the thief AND the victim must log in
 *         again, and the stolen token is worthless.</li>
 * </ul>
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    /** 256 random bits: impossible to guess, so a fast hash (SHA-256) is enough to store it. */
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository tokens;
    private final AppUserRepository users;
    private final JwtProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository tokens, AppUserRepository users, JwtProperties properties,
                               Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Result of a rotation: the user to issue a new access token for, and the refresh token
     * (plain text, for the client) that replaces the used one.
     */
    public record Rotation(AppUser user, String nextRefreshToken) {
    }

    /** At login: starts a new session (family). Returns the plain token for the client. */
    @Transactional
    public String issueForNewSession(AppUser user) {
        return issue(user.getId(), UUID.randomUUID().toString());
    }

    /**
     * Uses a refresh token: revokes it and returns a new one for the same session.
     * noRollbackFor: when reuse is detected we revoke the family AND throw; without it the
     * exception would roll the revocation back.
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public Rotation rotate(String presentedToken) {
        Instant now = clock.instant();
        RefreshToken current = tokens.findByTokenHash(hash(presentedToken))
                .orElseThrow(() -> new InvalidRefreshTokenException("Unknown refresh token"));

        if (current.isRevoked()) {
            int revoked = tokens.revokeFamily(current.getFamilyId(), now);
            log.warn("Refresh token reuse detected for user {}: session {} revoked ({} tokens)",
                    current.getUserId(), current.getFamilyId(), revoked);
            throw new InvalidRefreshTokenException("Refresh token already used; log in again");
        }
        if (current.isExpired(now)) {
            throw new InvalidRefreshTokenException("Refresh token expired; log in again");
        }
        AppUser user = users.findById(current.getUserId())
                .filter(AppUser::isEnabled)
                .orElseThrow(() -> new InvalidRefreshTokenException("User not found or disabled"));

        current.revoke(now);
        return new Rotation(user, issue(user.getId(), current.getFamilyId()));
    }

    /** Logout: ends the session of this token. Unknown tokens are ignored (nothing to end). */
    @Transactional
    public void revokeSession(String presentedToken) {
        tokens.findByTokenHash(hash(presentedToken))
                .ifPresent(token -> tokens.revokeFamily(token.getFamilyId(), clock.instant()));
    }

    /** Housekeeping: expired tokens are useless, delete them once a day. */
    @Scheduled(fixedDelayString = "${security.jwt.refresh-cleanup-interval:1d}")
    @Transactional
    public void deleteExpiredTokens() {
        int deleted = tokens.deleteExpiredBefore(clock.instant());
        if (deleted > 0) {
            log.info("Deleted {} expired refresh tokens", deleted);
        }
    }

    private String issue(Long userId, String familyId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        tokens.save(new RefreshToken(userId, hash(value), familyId, now, now.plus(properties.refreshExpiry())));
        return value;
    }

    /** Hex SHA-256. Unlike BCrypt it has no random salt, so the hash can be looked up directly. */
    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }
}
