package dev.ltidemo.nrps;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One person in a course roster.
 *
 * <p>{@code user_id} is the same value that arrives as {@code sub} in that person's launch. That
 * single fact is what makes NRPS useful: it lets a tool reconcile a roster it pulled with users
 * it has actually seen, using the identifier it already stores. Matching roster entries to
 * users by name or email — the obvious-looking approach — fails for exactly the reasons it fails
 * at launch, and here it fails silently across a whole class at once.
 *
 * <p>{@code email} and {@code name} are frequently absent. Whether they appear depends on the
 * institution's privacy configuration, not on the scopes granted, so code that needs them has
 * to cope with their absence rather than treat it as an error.
 *
 * <p>{@code status} distinguishes Active from Inactive and Deleted members. A roster sync that
 * ignores it will keep marking withdrawn students as outstanding.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Member(
        @JsonProperty("user_id") String userId,
        @JsonProperty("roles") List<String> roles,
        @JsonProperty("name") String name,
        @JsonProperty("given_name") String givenName,
        @JsonProperty("family_name") String familyName,
        @JsonProperty("email") String email,
        @JsonProperty("status") String status,
        @JsonProperty("lis_person_sourcedid") String personSourcedId
) {

    public boolean isActive() {
        return status == null || "Active".equalsIgnoreCase(status);
    }

    public boolean isInstructor() {
        return roles != null && roles.stream()
                .anyMatch(r -> r.endsWith("#Instructor") || r.endsWith("#Administrator")
                        || r.equals("Instructor"));
    }

    public boolean isLearner() {
        return roles != null && roles.stream()
                .anyMatch(r -> r.endsWith("#Learner") || r.equals("Learner"));
    }

    public String displayName() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        if (givenName != null || familyName != null) {
            return ((givenName == null ? "" : givenName) + " "
                    + (familyName == null ? "" : familyName)).trim();
        }
        // Institutions withhold names routinely. The user_id is always there.
        return userId;
    }
}
