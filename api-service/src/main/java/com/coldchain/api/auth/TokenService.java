package com.coldchain.api.auth;

import com.coldchain.api.user.AppUser;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Creates the signed JWT returned by the login endpoint. Example payload:
 * <pre>{"iss":"coldchain-api","sub":"admin@nile-pharmacy.local","roles":["TENANT_ADMIN"],
 *  "tenantId":"nile-pharmacy","iat":1760000000,"exp":1760000900}</pre>
 * The tenantId in the token is the ONLY source of tenant information for later requests.
 */
@Service
public class TokenService {

    public static final String ROLES_CLAIM = "roles";
    public static final String TENANT_CLAIM = "tenantId";

    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public String issueToken(AppUser user) {
        Instant now = clock.instant();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(user.getUsername())
                .issuedAt(now)
                .expiresAt(now.plus(properties.expiry()))
                .claim(ROLES_CLAIM, List.of(user.getRole().name()));
        if (user.getTenantId() != null) {
            claims.claim(TENANT_CLAIM, user.getTenantId());
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
