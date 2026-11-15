package com.coldchain.api.auth;

import com.coldchain.api.user.AppUser;
import com.coldchain.api.user.AppUserRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefreshTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final String PRESENTED = "presented-token";

    private final RefreshTokenRepository tokens = mock(RefreshTokenRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final RefreshTokenService service = new RefreshTokenService(tokens, users,
            new JwtProperties("a-test-secret-that-is-at-least-32-bytes-long", "test", Duration.ofMinutes(15),
                    Duration.ofDays(7)),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void anExpiredTokenIsRejected() {
        stored(new RefreshToken(1L, "hash", "family", NOW.minus(Duration.ofDays(8)), NOW.minusSeconds(1)));

        assertThatThrownBy(() -> service.rotate(PRESENTED))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessage("Refresh token expired; log in again");
        verify(tokens, never()).save(any());
    }

    @Test
    void aTokenOfADisabledUserIsRejected() {
        stored(new RefreshToken(1L, "hash", "family", NOW, NOW.plus(Duration.ofDays(7))));
        AppUser disabled = mock(AppUser.class);
        when(disabled.isEnabled()).thenReturn(false);
        when(users.findById(1L)).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> service.rotate(PRESENTED)).isInstanceOf(InvalidRefreshTokenException.class);
        verify(tokens, never()).save(any());
    }

    @Test
    void reusingARevokedTokenRevokesTheWholeFamily() {
        RefreshToken used = new RefreshToken(1L, "hash", "family-42", NOW, NOW.plus(Duration.ofDays(7)));
        used.revoke(NOW.minusSeconds(60));
        stored(used);

        assertThatThrownBy(() -> service.rotate(PRESENTED)).isInstanceOf(InvalidRefreshTokenException.class);
        verify(tokens).revokeFamily("family-42", NOW);
    }

    @Test
    void rotationRevokesTheUsedTokenAndIssuesADifferentOneInTheSameFamily() {
        RefreshToken current = new RefreshToken(1L, "hash", "family-7", NOW, NOW.plus(Duration.ofDays(7)));
        stored(current);
        AppUser user = mock(AppUser.class);
        when(user.isEnabled()).thenReturn(true);
        when(user.getId()).thenReturn(1L);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(tokens.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        RefreshTokenService.Rotation rotation = service.rotate(PRESENTED);

        assertThat(current.getRevokedAt()).isEqualTo(NOW);
        assertThat(rotation.nextRefreshToken()).isNotEqualTo(PRESENTED).hasSize(43); // 32 bytes, Base64 URL
        verify(tokens).save(argThat(saved ->
                saved.getFamilyId().equals("family-7")
                        && saved.getExpiresAt().equals(NOW.plus(Duration.ofDays(7)))));
    }

    @Test
    void hashIsHexSha256() {
        // echo -n abc | sha256sum
        assertThat(RefreshTokenService.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    private void stored(RefreshToken token) {
        when(tokens.findByTokenHash(RefreshTokenService.hash(PRESENTED))).thenReturn(Optional.of(token));
    }
}
