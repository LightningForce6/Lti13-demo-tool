package dev.ltidemo.launch;

import dev.ltidemo.config.LtiProperties;
import dev.ltidemo.config.PlatformRegistration;
import dev.ltidemo.config.PlatformRegistry;
import dev.ltidemo.security.StateService;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Step one of the launch: third-party initiated login.
 *
 * <p>The platform sends the browser here first, with no token. We reply with a redirect to the
 * platform's own authentication endpoint, carrying a {@code state} and {@code nonce} we just
 * minted. The platform then posts the signed id_token to {@code /lti/launch}.
 *
 * <p>The round trip exists so that the tool, not the platform, chooses the nonce. A token whose
 * nonce the tool didn't pick is a token the tool can't prove is fresh.
 */
@Controller
public class OidcLoginController {

    private static final Logger log = LoggerFactory.getLogger(OidcLoginController.class);

    static final String STATE_COOKIE = "lti_state";

    private final PlatformRegistry registry;
    private final StateService stateService;
    private final LtiProperties properties;

    public OidcLoginController(PlatformRegistry registry, StateService stateService,
                               LtiProperties properties) {
        this.registry = registry;
        this.stateService = stateService;
        this.properties = properties;
    }

    // Platforms differ on the verb: Canvas and Moodle POST, some others GET. Accept both.
    @GetMapping("/lti/login")
    public String loginGet(@RequestParam("iss") String iss,
                           @RequestParam(value = "login_hint", required = false) String loginHint,
                           @RequestParam(value = "target_link_uri", required = false) String targetLinkUri,
                           @RequestParam(value = "lti_message_hint", required = false) String messageHint,
                           @RequestParam(value = "client_id", required = false) String clientId,
                           HttpServletResponse response) {
        return login(iss, loginHint, targetLinkUri, messageHint, clientId, response);
    }

    @PostMapping("/lti/login")
    public String loginPost(@RequestParam("iss") String iss,
                            @RequestParam(value = "login_hint", required = false) String loginHint,
                            @RequestParam(value = "target_link_uri", required = false) String targetLinkUri,
                            @RequestParam(value = "lti_message_hint", required = false) String messageHint,
                            @RequestParam(value = "client_id", required = false) String clientId,
                            HttpServletResponse response) {
        return login(iss, loginHint, targetLinkUri, messageHint, clientId, response);
    }

    private String login(String iss, String loginHint, String targetLinkUri, String messageHint,
                         String clientId, HttpServletResponse response) {

        PlatformRegistration platform = registry.find(iss, clientId).orElseThrow(() ->
                new LaunchValidationException("Login initiation from unregistered issuer: " + iss));

        StateService.StateAndNonce sn = stateService.issue();

        // The state cookie is how we later prove the launch came back to the same browser.
        //
        // SameSite=None is not optional: LTI tools are rendered inside an LMS iframe, which
        // makes this a third-party cookie context. A default (Lax) cookie is simply not sent
        // back, and the launch fails with a state mismatch that looks like a code bug.
        // SameSite=None requires Secure, so the tool must be on HTTPS — Chrome exempts
        // http://localhost, which is why local development works and a plain-HTTP deployment
        // does not.
        ResponseCookie cookie = ResponseCookie.from(STATE_COOKIE, sn.state())
                .httpOnly(true)
                .secure(true)
                .sameSite("None")
                .path("/")
                .maxAge(properties.getStateTtlSeconds())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        String redirectUri = targetLinkUri != null && !targetLinkUri.isBlank()
                ? targetLinkUri
                : properties.launchUrl();

        UriComponentsBuilder auth = UriComponentsBuilder.fromUriString(platform.getOidcAuthUrl())
                .queryParam("scope", "openid")
                .queryParam("response_type", "id_token")
                .queryParam("response_mode", "form_post")
                .queryParam("prompt", "none")
                .queryParam("client_id", platform.getClientId())
                .queryParam("redirect_uri", enc(redirectUri))
                .queryParam("state", sn.state())
                .queryParam("nonce", sn.nonce());

        if (loginHint != null) {
            auth.queryParam("login_hint", enc(loginHint));
        }
        if (messageHint != null) {
            auth.queryParam("lti_message_hint", enc(messageHint));
        }

        log.debug("Initiating launch with {} (redirect_uri={})", platform.getName(), redirectUri);
        return "redirect:" + auth.build(true).toUriString();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
