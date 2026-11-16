package dev.ltidemo.service;

/** OAuth 2.0 scopes for the LTI Advantage services. */
public final class ServiceScopes {

    private ServiceScopes() {}

    // Assignment and Grade Services
    public static final String LINEITEM = "https://purl.imsglobal.org/spec/lti-ags/scope/lineitem";
    public static final String LINEITEM_READONLY =
            "https://purl.imsglobal.org/spec/lti-ags/scope/lineitem.readonly";
    public static final String RESULT_READONLY =
            "https://purl.imsglobal.org/spec/lti-ags/scope/result.readonly";
    public static final String SCORE = "https://purl.imsglobal.org/spec/lti-ags/scope/score";

    // Names and Role Provisioning Services
    public static final String CONTEXT_MEMBERSHIP_READONLY =
            "https://purl.imsglobal.org/spec/lti-nrps/scope/contextmembership.readonly";
}
