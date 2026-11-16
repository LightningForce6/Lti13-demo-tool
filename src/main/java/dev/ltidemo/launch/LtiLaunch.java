package dev.ltidemo.launch;

import dev.ltidemo.config.PlatformRegistration;

import java.util.List;
import java.util.Map;

/**
 * A validated launch, reduced to the fields an application actually consumes.
 *
 * <p>The field that matters most here is {@link #stableUserKey()}. The identity of a launching
 * user is the tuple (issuer, client_id, deployment_id, sub) and nothing else. Not email, which
 * institutions routinely withhold under privacy settings and which Moodle and Brightspace will
 * happily send as blank. Not name. Matching users on email is the single most common way an
 * LTI integration ends up with two accounts for one student, or — worse — one account shared
 * by two students who happen to have had an address reused by their institution.
 *
 * <p>The correct pattern is: store this tuple the first time you see it, map it to your own
 * user record permanently, and if a user already exists in your system, link the two with one
 * explicit confirmation rather than by inference.
 */
public record LtiLaunch(
        PlatformRegistration platform,
        String messageType,
        String deploymentId,
        String subject,
        String name,
        String email,
        List<String> roles,
        String contextId,
        String contextTitle,
        String resourceLinkId,
        String resourceLinkTitle,
        Map<String, Object> custom,
        Map<String, Object> deepLinkingSettings,
        Map<String, Object> agsEndpoint,
        Map<String, Object> nrpsEndpoint
) {

    public String stableUserKey() {
        return platform.getIssuer() + "|" + platform.getClientId() + "|" + deploymentId + "|" + subject;
    }

    public String stableContextKey() {
        return platform.getIssuer() + "|" + platform.getClientId() + "|" + deploymentId + "|" + contextId;
    }

    public boolean isInstructor() {
        return roles != null && roles.stream()
                .anyMatch(r -> r.equals(LtiClaims.ROLE_INSTRUCTOR)
                        || r.endsWith("#Instructor")
                        || r.endsWith("#Administrator"));
    }

    public boolean isDeepLinkingRequest() {
        return LtiClaims.MESSAGE_TYPE_DEEP_LINKING.equals(messageType);
    }

    public String displayName() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return "user " + (subject == null ? "unknown" : subject);
    }

    /** The task this launch points at, set by Deep Linking as a custom parameter. */
    public String taskId() {
        if (custom == null) {
            return null;
        }
        Object v = custom.get("task_id");
        return v == null ? null : String.valueOf(v);
    }
}
