package dev.ltidemo.config;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Resolves the platform a launch claims to come from.
 *
 * <p>The lookup key is issuer + client_id, not issuer alone. One LMS host can issue several
 * client IDs for the same tool (a test registration and a live one, or one per sub-account),
 * and they are genuinely different tenants with different keys.
 */
@Component
public class PlatformRegistry {

    private final LtiProperties properties;

    public PlatformRegistry(LtiProperties properties) {
        this.properties = properties;
    }

    public Optional<PlatformRegistration> find(String issuer, String clientId) {
        if (issuer == null) {
            return Optional.empty();
        }
        List<PlatformRegistration> candidates = properties.getPlatforms().stream()
                .filter(p -> issuer.equals(p.getIssuer()))
                .toList();

        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (clientId == null) {
            // The OIDC initiation request is allowed to omit client_id when the platform has
            // only registered the tool once. If we hold several registrations for this issuer
            // we cannot guess, and failing loudly beats picking the wrong tenant.
            return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
        }
        return candidates.stream()
                .filter(p -> clientId.equals(p.getClientId()))
                .findFirst();
    }

    public List<PlatformRegistration> all() {
        return properties.getPlatforms();
    }
}
