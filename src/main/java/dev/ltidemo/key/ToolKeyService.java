package dev.ltidemo.key;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.JWSAlgorithm;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Holds this tool's signing keys and publishes them as a JWKS.
 *
 * <p>The design point worth noticing: the key set is a <em>list</em>, and the JWKS endpoint
 * serves every key in it while only the first is used for signing. That is what makes rotation
 * a deploy rather than an outage. To rotate, you prepend a new key and keep the old one
 * published until every platform's cache has expired, then drop it. If the JWKS only ever
 * carried one key, rotating it would invalidate in-flight Deep Linking responses and would have
 * to be coordinated with every LMS vendor you integrate with.
 *
 * <p>Keys are generated on startup if no key file is configured, which is fine for a demo and
 * wrong for production: restart the process and every platform's cached copy of your public key
 * is stale. In production, load from a secret manager and persist.
 */
@Service
public class ToolKeyService {

    private static final Logger log = LoggerFactory.getLogger(ToolKeyService.class);

    @Value("${lti.key-file:}")
    private String keyFile;

    private final List<RSAKey> keys = new ArrayList<>();

    @PostConstruct
    void init() throws JOSEException {
        if (keyFile != null && !keyFile.isBlank()) {
            try {
                String json = Files.readString(Path.of(keyFile));
                JWKSet loaded = JWKSet.parse(json);
                loaded.getKeys().forEach(k -> keys.add((RSAKey) k));
                log.info("Loaded {} signing key(s) from {}", keys.size(), keyFile);
                return;
            } catch (IOException | java.text.ParseException e) {
                log.warn("Could not read key file {} ({}), generating an ephemeral key instead",
                        keyFile, e.getMessage());
            }
        }
        keys.add(generate());
        log.info("Generated ephemeral signing key with kid={}. Set lti.key-file to persist it.",
                keys.get(0).getKeyID());
    }

    private RSAKey generate() throws JOSEException {
        return new RSAKeyGenerator(2048)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .keyIDFromThumbprint(true)
                .generate();
    }

    /** The key new signatures are made with — always the first in the list. */
    public RSAKey signingKey() {
        return keys.get(0);
    }

    /** Public half of every key, current and retiring. This is what the JWKS endpoint serves. */
    public Map<String, Object> publicJwks() {
        JWKSet set = new JWKSet(new ArrayList<JWK>(keys)).toPublicJWKSet();
        return set.toJSONObject();
    }
}
