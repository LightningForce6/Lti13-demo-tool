package dev.ltidemo.launch;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import dev.ltidemo.config.LtiProperties;
import dev.ltidemo.config.PlatformRegistration;
import dev.ltidemo.config.PlatformRegistry;
import dev.ltidemo.security.StateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.MalformedURLException;
import java.net.URL;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifies an incoming id_token and turns it into an {@link LtiLaunch}.
 *
 * <p>Every check below is written out rather than delegated, because the checks are the
 * integration. A launch that is merely "signed by someone" proves nothing: the useful question
 * is whether it was signed by <em>this</em> platform, addressed to <em>this</em> tool, from a
 * deployment we recognise, recently, and only once.
 */
@Service
public class IdTokenValidator {

    private static final Logger log = LoggerFactory.getLogger(IdTokenValidator.class);

    private final PlatformRegistry registry;
    private final LtiProperties properties;
    private final StateService stateService;

    /**
     * One JWK source per platform, cached.
     *
     * <p>{@code JWKSourceBuilder} refreshes on a cache miss, which is the behaviour that matters:
     * when a platform rotates its keys, the first token signed with the new {@code kid} triggers
     * a refetch instead of a failed launch. Rate limiting is built in so an attacker can't turn
     * unknown kids into a request amplifier against the platform.
     */
    private final Map<String, JWKSource<SecurityContext>> jwkSources = new ConcurrentHashMap<>();

    public IdTokenValidator(PlatformRegistry registry, LtiProperties properties,
                            StateService stateService) {
        this.registry = registry;
        this.properties = properties;
        this.stateService = stateService;
    }

    public LtiLaunch validate(String idToken, String state, String stateCookie) {
        if (idToken == null || idToken.isBlank()) {
            throw new LaunchValidationException("No id_token in the launch request.");
        }

        // 1. CSRF: the state returned by the platform must match the one we set in a cookie
        //    when we started the flow, and must still be outstanding on the server.
        if (state == null || stateCookie == null || !state.equals(stateCookie)) {
            throw new LaunchValidationException(
                    "state parameter does not match the state cookie. If this happens only in an "
                    + "LMS iframe, the cookie is being dropped: it needs SameSite=None; Secure.");
        }
        String expectedNonce = stateService.redeem(state).orElseThrow(() ->
                new LaunchValidationException("Unknown or expired state. The launch took too long, "
                        + "or this state has already been used."));

        // 2. Parse without trusting anything yet, so we can find out who claims to have sent it.
        SignedJWT jwt;
        JWTClaimsSet unverified;
        try {
            jwt = SignedJWT.parse(idToken);
            unverified = jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw new LaunchValidationException("id_token is not a well-formed JWT.", e);
        }

        String issuer = unverified.getIssuer();
        String clientId = firstAudience(unverified);
        PlatformRegistration platform = registry.find(issuer, clientId).orElseThrow(() ->
                new LaunchValidationException("No registration for issuer '" + issuer
                        + "' and client_id '" + clientId + "'. This platform is not registered "
                        + "with this tool."));

        // 3. Signature, against the platform's published keys. Everything before this point is
        //    an unverified assertion; everything after it is trustworthy.
        JWTClaimsSet claims;
        try {
            ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
            processor.setJWSKeySelector(new JWSVerificationKeySelector<>(
                    JWSAlgorithm.RS256, jwkSource(platform)));
            // We check temporal and identity claims ourselves below, so that failures name the
            // specific claim rather than producing a generic "token rejected".
            processor.setJWTClaimsSetVerifier((c, ctx) -> {});
            claims = processor.process(jwt, null);
        } catch (Exception e) {
            throw new LaunchValidationException(
                    "id_token signature verification failed against " + platform.getJwksUrl(), e);
        }

        // 4. Audience. The token must be addressed to us specifically.
        List<String> audience = claims.getAudience();
        if (audience == null || !audience.contains(platform.getClientId())) {
            throw new LaunchValidationException("aud does not contain our client_id ("
                    + platform.getClientId() + ").");
        }
        // When aud holds several values, azp must name the one in use.
        if (audience.size() > 1) {
            Object azp = claims.getClaim("azp");
            if (azp == null || !platform.getClientId().equals(String.valueOf(azp))) {
                throw new LaunchValidationException(
                        "aud has multiple values and azp does not identify our client_id.");
            }
        }

        // 5. Freshness.
        Instant now = Instant.now();
        long skew = properties.getClockSkewSeconds();
        Date exp = claims.getExpirationTime();
        if (exp == null || exp.toInstant().plusSeconds(skew).isBefore(now)) {
            throw new LaunchValidationException("id_token has expired.");
        }
        Date iat = claims.getIssueTime();
        if (iat == null || iat.toInstant().minusSeconds(skew).isAfter(now)) {
            throw new LaunchValidationException("id_token iat is in the future; check clock skew.");
        }

        // 6. Nonce: present, matching the one we minted, and not previously spent.
        //    This is what stops a captured launch being replayed while its signature is still valid.
        Object nonceClaim = claims.getClaim("nonce");
        String nonce = nonceClaim == null ? null : String.valueOf(nonceClaim);
        if (nonce == null || !nonce.equals(expectedNonce)) {
            throw new LaunchValidationException("nonce does not match the value issued for this state.");
        }
        if (!stateService.claimNonce(nonce)) {
            throw new LaunchValidationException("This nonce has already been used — replayed launch.");
        }

        // 7. LTI-specific claims.
        String version = str(claims, LtiClaims.VERSION);
        if (!LtiClaims.LTI_VERSION.equals(version)) {
            throw new LaunchValidationException("Unsupported LTI version: " + version);
        }
        String deploymentId = str(claims, LtiClaims.DEPLOYMENT_ID);
        if (deploymentId == null || !platform.allowsDeployment(deploymentId)) {
            throw new LaunchValidationException("Unrecognised deployment_id '" + deploymentId
                    + "' for this platform. Add it to the registration if this installation is new.");
        }
        String messageType = str(claims, LtiClaims.MESSAGE_TYPE);
        if (!LtiClaims.MESSAGE_TYPE_RESOURCE_LINK.equals(messageType)
                && !LtiClaims.MESSAGE_TYPE_DEEP_LINKING.equals(messageType)) {
            throw new LaunchValidationException("Unsupported message type: " + messageType);
        }

        String sub = claims.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new LaunchValidationException("id_token has no sub claim; cannot identify the user.");
        }

        Map<String, Object> context = map(claims, LtiClaims.CONTEXT);
        Map<String, Object> resourceLink = map(claims, LtiClaims.RESOURCE_LINK);

        log.info("Validated {} from {} (deployment {}) for sub {}",
                messageType, platform.getName(), deploymentId, sub);

        return new LtiLaunch(
                platform,
                messageType,
                deploymentId,
                sub,
                str(claims, "name"),
                str(claims, "email"),
                stringList(claims, LtiClaims.ROLES),
                context == null ? null : String.valueOf(context.get("id")),
                context == null ? null : String.valueOf(context.getOrDefault("title", "")),
                resourceLink == null ? null : String.valueOf(resourceLink.get("id")),
                resourceLink == null ? null : String.valueOf(resourceLink.getOrDefault("title", "")),
                map(claims, LtiClaims.CUSTOM),
                map(claims, LtiClaims.DEEP_LINKING_SETTINGS),
                map(claims, LtiClaims.AGS_ENDPOINT),
                map(claims, LtiClaims.NRPS_ENDPOINT)
        );
    }

    private JWKSource<SecurityContext> jwkSource(PlatformRegistration platform) {
        return jwkSources.computeIfAbsent(platform.getIssuer() + "|" + platform.getClientId(), k -> {
            try {
                return JWKSourceBuilder.<SecurityContext>create(new URL(platform.getJwksUrl()))
                        .retrying(true)
                        .build();
            } catch (MalformedURLException e) {
                throw new LaunchValidationException(
                        "Bad jwks-url for platform " + platform.getName(), e);
            }
        });
    }

    private static String firstAudience(JWTClaimsSet claims) {
        List<String> aud = claims.getAudience();
        return aud == null || aud.isEmpty() ? null : aud.get(0);
    }

    private static String str(JWTClaimsSet claims, String name) {
        Object v = claims.getClaim(name);
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(JWTClaimsSet claims, String name) {
        Object v = claims.getClaim(name);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    private static List<String> stringList(JWTClaimsSet claims, String name) {
        Object v = claims.getClaim(name);
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            list.forEach(item -> out.add(String.valueOf(item)));
        }
        return out;
    }
}
