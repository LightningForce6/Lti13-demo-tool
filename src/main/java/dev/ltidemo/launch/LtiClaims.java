package dev.ltidemo.launch;

/** The claim URIs defined by the LTI 1.3 core and Deep Linking specifications. */
public final class LtiClaims {

    private LtiClaims() {}

    public static final String MESSAGE_TYPE = "https://purl.imsglobal.org/spec/lti/claim/message_type";
    public static final String VERSION = "https://purl.imsglobal.org/spec/lti/claim/version";
    public static final String DEPLOYMENT_ID = "https://purl.imsglobal.org/spec/lti/claim/deployment_id";
    public static final String TARGET_LINK_URI = "https://purl.imsglobal.org/spec/lti/claim/target_link_uri";
    public static final String RESOURCE_LINK = "https://purl.imsglobal.org/spec/lti/claim/resource_link";
    public static final String CONTEXT = "https://purl.imsglobal.org/spec/lti/claim/context";
    public static final String ROLES = "https://purl.imsglobal.org/spec/lti/claim/roles";
    public static final String CUSTOM = "https://purl.imsglobal.org/spec/lti/claim/custom";
    public static final String TOOL_PLATFORM = "https://purl.imsglobal.org/spec/lti/claim/tool_platform";
    public static final String LAUNCH_PRESENTATION = "https://purl.imsglobal.org/spec/lti/claim/launch_presentation";

    public static final String DEEP_LINKING_SETTINGS =
            "https://purl.imsglobal.org/spec/lti-dl/claim/deep_linking_settings";
    public static final String CONTENT_ITEMS =
            "https://purl.imsglobal.org/spec/lti-dl/claim/content_items";
    public static final String DEEP_LINKING_DATA =
            "https://purl.imsglobal.org/spec/lti-dl/claim/data";

    // Service endpoints. Not used by launch or Deep Linking, but captured at launch time
    // because they are what AGS and NRPS need later.
    public static final String AGS_ENDPOINT =
            "https://purl.imsglobal.org/spec/lti-ags/claim/endpoint";
    public static final String NRPS_ENDPOINT =
            "https://purl.imsglobal.org/spec/lti-nrps/claim/namesroleservice";

    public static final String MESSAGE_TYPE_RESOURCE_LINK = "LtiResourceLinkRequest";
    public static final String MESSAGE_TYPE_DEEP_LINKING = "LtiDeepLinkingRequest";
    public static final String MESSAGE_TYPE_DEEP_LINKING_RESPONSE = "LtiDeepLinkingResponse";
    public static final String LTI_VERSION = "1.3.0";

    public static final String ROLE_INSTRUCTOR =
            "http://purl.imsglobal.org/vocab/lis/v2/membership#Instructor";
    public static final String ROLE_LEARNER =
            "http://purl.imsglobal.org/vocab/lis/v2/membership#Learner";
}
