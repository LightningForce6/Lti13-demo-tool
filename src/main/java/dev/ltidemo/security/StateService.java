package dev.ltidemo.security;

import dev.ltidemo.config.LtiProperties;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues and redeems the {@code state}/{@code nonce} pair for a launch.
 *
 * <p>Why both exist, since this is the question people get wrong:
 *
 * <ul>
 *   <li>{@code state} is CSRF protection for the redirect. We mint it, hand it to the platform,
 *       and the platform hands it back. We also set it in a cookie, so when the launch arrives
 *       we can confirm it came from a browser we actually sent somewhere — not from an attacker
 *       who replayed a captured form post at a logged-in user.
 *   <li>{@code nonce} is replay protection for the token itself. It lives inside the signed
 *       id_token, so the platform's signature covers it. We record it as used on first sight;
 *       a second launch carrying the same nonce is a replay and is rejected even though its
 *       signature is perfectly valid.
 * </ul>
 *
 * <p>In-memory with a sweep on access is correct for a single instance and wrong behind a load
 * balancer: launch and callback can land on different nodes, and anything you don't share is a
 * nonce you aren't really checking. Production wants Redis or a table with a TTL index.
 */
@Service
public class StateService {

    private record Pending(String nonce, Instant expiresAt) {}

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, Instant> usedNonces = new ConcurrentHashMap<>();
    private final LtiProperties properties;

    public StateService(LtiProperties properties) {
        this.properties = properties;
    }

    public record StateAndNonce(String state, String nonce) {}

    public StateAndNonce issue() {
        sweep();
        String state = randomToken();
        String nonce = randomToken();
        pending.put(state, new Pending(nonce,
                Instant.now().plusSeconds(properties.getStateTtlSeconds())));
        return new StateAndNonce(state, nonce);
    }

    /** Consume the state. Returns the nonce we expect to find inside the id_token. */
    public Optional<String> redeem(String state) {
        sweep();
        if (state == null) {
            return Optional.empty();
        }
        Pending p = pending.remove(state);   // single use
        if (p == null || p.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(p.nonce());
    }

    /**
     * Record a nonce as spent. Returns false if it had already been used, which means the token
     * is being replayed.
     */
    public boolean claimNonce(String nonce) {
        sweep();
        if (nonce == null || nonce.isBlank()) {
            return false;
        }
        Instant expiry = Instant.now().plusSeconds(properties.getStateTtlSeconds());
        return usedNonces.putIfAbsent(nonce, expiry) == null;
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void sweep() {
        Instant now = Instant.now();
        pending.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
        usedNonces.entrySet().removeIf(e -> e.getValue().isBefore(now));
    }
}
