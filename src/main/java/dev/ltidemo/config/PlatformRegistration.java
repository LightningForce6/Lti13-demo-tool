package dev.ltidemo.config;

import java.util.ArrayList;
import java.util.List;

/**
 * One LMS platform that is allowed to launch this tool.
 *
 * <p>In a real multi-tenant deployment this is a database row, not config — one record per
 * institution, created when that institution installs the tool. It is modelled as a first-class
 * object here because almost every bug in a multi-tenant LTI integration traces back to code
 * that assumed a single platform.
 *
 * <p>Note that {@code deploymentIds} is a list. A single institution can install the same tool
 * more than once (per-department, per-campus), and each installation gets its own deployment ID.
 * Treating it as a single value is a common and expensive mistake.
 *
 * <p>{@code tokenUrl} is unused by launch and Deep Linking. It is here because it is the
 * credential needed later for AGS (grade passback) and NRPS (roster) calls — storing it from
 * the first launch is what keeps those a later addition rather than a schema migration.
 */
public class PlatformRegistration {

    /** Human-readable label, for logs and the admin view. e.g. "Canvas test instance". */
    private String name;

    /** The platform's issuer identifier. Must match the {@code iss} claim exactly. */
    private String issuer;

    /** The client_id this platform assigned to our tool. Must appear in the {@code aud} claim. */
    private String clientId;

    /** Every deployment ID this platform may legitimately launch us from. */
    private List<String> deploymentIds = new ArrayList<>();

    /** Where we redirect the browser to begin the OIDC authentication request. */
    private String oidcAuthUrl;

    /** Where we fetch this platform's public keys to verify id_token signatures. */
    private String jwksUrl;

    /** OAuth 2.0 token endpoint, for AGS and NRPS access tokens. Not used by launch. */
    private String tokenUrl;

    /**
     * What to put in the {@code aud} of a client assertion sent to {@link #tokenUrl}.
     *
     * <p>Left blank, the token URL itself is used, which is what most platforms expect. A few
     * want the platform's issuer instead and will reject a correct assertion with a bare
     * {@code invalid_client} — an error that tells you nothing and costs an afternoon. Set it
     * explicitly for those.
     */
    private String tokenAudience;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public List<String> getDeploymentIds() {
        return deploymentIds;
    }

    public void setDeploymentIds(List<String> deploymentIds) {
        this.deploymentIds = deploymentIds;
    }

    public String getOidcAuthUrl() {
        return oidcAuthUrl;
    }

    public void setOidcAuthUrl(String oidcAuthUrl) {
        this.oidcAuthUrl = oidcAuthUrl;
    }

    public String getJwksUrl() {
        return jwksUrl;
    }

    public void setJwksUrl(String jwksUrl) {
        this.jwksUrl = jwksUrl;
    }

    public String getTokenUrl() {
        return tokenUrl;
    }

    public void setTokenUrl(String tokenUrl) {
        this.tokenUrl = tokenUrl;
    }

    public String getTokenAudience() {
        return tokenAudience;
    }

    public void setTokenAudience(String tokenAudience) {
        this.tokenAudience = tokenAudience;
    }

    public boolean allowsDeployment(String deploymentId) {
        return deploymentIds != null && deploymentIds.contains(deploymentId);
    }
}
