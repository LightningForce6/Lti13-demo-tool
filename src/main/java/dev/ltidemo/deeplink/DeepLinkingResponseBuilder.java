package dev.ltidemo.deeplink;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.ltidemo.config.LtiProperties;
import dev.ltidemo.key.ToolKeyService;
import dev.ltidemo.launch.LtiClaims;
import dev.ltidemo.launch.LtiLaunch;
import dev.ltidemo.task.Task;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the signed JWT that carries an instructor's content choices back to the LMS.
 *
 * <p>This is the one place where the tool signs rather than verifies, and it is the reason the
 * tool has to publish a JWKS at all: the platform fetches our public key to check this token.
 * A tool that only ever consumed launches would not need keys of its own.
 *
 * <p>Note the direction of {@code iss} and {@code aud}. On the way in, the platform is the
 * issuer and our client_id is the audience. On the way out they swap: we issue as our client_id
 * and address the platform's issuer. Getting this backwards produces a response the LMS rejects
 * with no useful diagnostics, and it is a very common first-attempt bug.
 */
@Service
public class DeepLinkingResponseBuilder {

    private final ToolKeyService keys;
    private final LtiProperties properties;
    private final SecureRandom random = new SecureRandom();

    public DeepLinkingResponseBuilder(ToolKeyService keys, LtiProperties properties) {
        this.keys = keys;
        this.properties = properties;
    }

    public String build(LtiLaunch launch, List<Task> selected) {
        Map<String, Object> settings = launch.deepLinkingSettings();
        if (settings == null) {
            throw new IllegalStateException(
                    "This launch carried no deep_linking_settings claim, so there is nowhere to "
                    + "return content to.");
        }

        List<Map<String, Object>> contentItems = new ArrayList<>();
        for (Task task : selected) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "ltiResourceLink");
            item.put("title", task.title());
            item.put("text", task.description());
            // target_link_uri for this item. The platform will launch this URL later.
            item.put("url", properties.launchUrl());
            // Custom parameters are how the later resource link launch knows which task it is.
            // They come back in the custom claim of that launch's id_token, signed by the
            // platform — which is why this is safe to trust and a query string would not be.
            item.put("custom", Map.of("task_id", task.id()));
            contentItems.add(item);
        }

        Date now = new Date();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(launch.platform().getClientId())
                .audience(launch.platform().getIssuer())
                .issueTime(now)
                .expirationTime(new Date(now.getTime() + 300_000L))
                .claim("nonce", randomNonce())
                .claim("azp", launch.platform().getClientId())
                .claim(LtiClaims.DEPLOYMENT_ID, launch.deploymentId())
                .claim(LtiClaims.MESSAGE_TYPE, LtiClaims.MESSAGE_TYPE_DEEP_LINKING_RESPONSE)
                .claim(LtiClaims.VERSION, LtiClaims.LTI_VERSION)
                .claim(LtiClaims.CONTENT_ITEMS, contentItems);

        // The platform may have handed us an opaque 'data' value in the request. It must come
        // back untouched; some platforms use it to re-associate the response with the editing
        // session, and dropping it silently breaks the return.
        Object data = settings.get("data");
        if (data != null) {
            claims.claim(LtiClaims.DEEP_LINKING_DATA, data);
        }

        try {
            RSAKey key = keys.signingKey();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                    claims.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign the Deep Linking response", e);
        }
    }

    public String returnUrl(LtiLaunch launch) {
        Map<String, Object> settings = launch.deepLinkingSettings();
        Object url = settings == null ? null : settings.get("deep_link_return_url");
        if (url == null) {
            throw new IllegalStateException("deep_linking_settings has no deep_link_return_url.");
        }
        return String.valueOf(url);
    }

    private String randomNonce() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
