package dev.ltidemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "lti")
public class LtiProperties {

    /**
     * Public base URL of this tool, with no trailing slash.
     *
     * <p>This must match what is registered in the LMS exactly. Brightspace in particular
     * compares redirect URIs by exact string, so a trailing slash here and not there is a
     * failed launch with an unhelpful error message.
     */
    private String toolBaseUrl = "http://localhost:8080";

    /** How long a launch state/nonce stays valid. Launches complete in seconds; keep it short. */
    private long stateTtlSeconds = 300;

    /** Tolerance for clock skew when checking {@code exp} and {@code iat}. */
    private long clockSkewSeconds = 60;

    private List<PlatformRegistration> platforms = new ArrayList<>();

    public String getToolBaseUrl() {
        return toolBaseUrl;
    }

    public void setToolBaseUrl(String toolBaseUrl) {
        // Normalise early: a trailing slash here silently breaks exact redirect URI matching.
        this.toolBaseUrl = toolBaseUrl != null && toolBaseUrl.endsWith("/")
                ? toolBaseUrl.substring(0, toolBaseUrl.length() - 1)
                : toolBaseUrl;
    }

    public long getStateTtlSeconds() {
        return stateTtlSeconds;
    }

    public void setStateTtlSeconds(long stateTtlSeconds) {
        this.stateTtlSeconds = stateTtlSeconds;
    }

    public long getClockSkewSeconds() {
        return clockSkewSeconds;
    }

    public void setClockSkewSeconds(long clockSkewSeconds) {
        this.clockSkewSeconds = clockSkewSeconds;
    }

    public List<PlatformRegistration> getPlatforms() {
        return platforms;
    }

    public void setPlatforms(List<PlatformRegistration> platforms) {
        this.platforms = platforms;
    }

    public String launchUrl() {
        return toolBaseUrl + "/lti/launch";
    }

    public String jwksUrl() {
        return toolBaseUrl + "/.well-known/jwks.json";
    }
}
