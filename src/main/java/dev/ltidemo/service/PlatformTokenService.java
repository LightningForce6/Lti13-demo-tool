package dev.ltidemo.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.ltidemo.config.PlatformRegistration;
import dev.ltidemo.key.ToolKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gets OAuth 2.0 access tokens for calling a platform's AGS and NRPS endpoints.
 *
 * <p>This is the layer that sits above the launch, and it is where most of the work in Phase 2
 * of an LTI project actually lives. The launch is browser-mediated and the platform signs it.
 * Service calls are the reverse: server to server, and <em>we</em> have to prove who we are.
 *
 * <p>The proof is a JWT we sign with the same key that backs our published JWKS, sent as a
 * {@code client_assertion} under the JWT-bearer grant. There is no client secret anywhere in
 * LTI 1.3 — which is the whole reason the tool needs a keypair and a JWKS endpoint, and why
 * key rotation has to work before any of this does.
 *
 * <p>Two things here are earned rather than obvious:
 *
 * <ul>
 *   <li>The {@code aud} of the client assertion. Most platforms want the token endpoint URL.
 *       Some want the platform's issuer instead, and will reject an otherwise perfect assertion
 *       with a bare {@code invalid_client}. It is configurable per registration for that reason.
 *   <li>Tokens are cached per scope set, not per platform. Platforms issue tokens scoped to
 *       exactly what you asked for, so a token fetched for {@code score} will be refused for a
 *       membership call. Caching by platform alone produces failures that look random.
 * </ul>
 */
@Service
public class PlatformTokenService {

    private static final Logger log = LoggerFactory.getLogger(PlatformTokenService.class);

    private record CachedToken(String value, Instant expiresAt) {}

    private final ToolKeyService keys;
    private final RestClient http = RestClient.create();
    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();

    public PlatformTokenService(ToolKeyService keys) {
        this.keys = keys;
    }

    public String accessToken(PlatformRegistration platform, List<String> scopes) {
        String scope = String.join(" ", scopes.stream().sorted().toList());
        String cacheKey = platform.getIssuer() + "|" + platform.getClientId() + "|" + scope;

        CachedToken cached = cache.get(cacheKey);
        // Refresh a little early: a token that expires mid-flight fails the call it was fetched for.
        if (cached != null && cached.expiresAt().isAfter(Instant.now().plusSeconds(30))) {
            return cached.value();
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
        form.add("client_assertion", clientAssertion(platform));
        form.add("scope", scope);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = http.post()
                .uri(platform.getTokenUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form)
                .retrieve()
                .body(Map.class);

        if (response == null || response.get("access_token") == null) {
            throw new ServiceCallException(
                    "Token endpoint " + platform.getTokenUrl() + " returned no access_token.");
        }

        String token = String.valueOf(response.get("access_token"));
        long expiresIn = response.get("expires_in") == null
                ? 3600L
                : Long.parseLong(String.valueOf(response.get("expires_in")));

        // A platform may grant fewer scopes than requested. Silently proceeding here is how you
        // get a 403 three calls later with nothing to point at, so say it at the source.
        Object granted = response.get("scope");
        if (granted != null && !String.valueOf(granted).isBlank()) {
            for (String requested : scopes) {
                if (!String.valueOf(granted).contains(requested)) {
                    log.warn("Platform {} did not grant scope {} (granted: {})",
                            platform.getName(), requested, granted);
                }
            }
        }

        cache.put(cacheKey, new CachedToken(token, Instant.now().plusSeconds(expiresIn)));
        log.debug("Obtained access token for {} with scope '{}'", platform.getName(), scope);
        return token;
    }

    private String clientAssertion(PlatformRegistration platform) {
        Date now = new Date();
        String audience = platform.getTokenAudience() != null
                && !platform.getTokenAudience().isBlank()
                ? platform.getTokenAudience()
                : platform.getTokenUrl();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(platform.getClientId())
                .subject(platform.getClientId())
                .audience(audience)
                .issueTime(now)
                .expirationTime(new Date(now.getTime() + 60_000L))
                // jti must be unique: platforms reject a replayed assertion, which is the point.
                .jwtID(UUID.randomUUID().toString())
                .build();

        try {
            RSAKey key = keys.signingKey();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new ServiceCallException("Could not sign the client assertion", e);
        }
    }
}
