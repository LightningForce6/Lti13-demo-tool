package dev.ltidemo.launch;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.ltidemo.key.ToolKeyService;
import dev.ltidemo.security.StateService;
import dev.ltidemo.stub.StubPlatform;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of the launch path with no LMS involved.
 *
 * <p>The trick that makes this possible: the test platform's {@code jwks-url} points at this
 * tool's own JWKS endpoint, and the test signs its id_tokens with the tool's own key. The
 * validator has no idea — it fetches a keyset over HTTP and verifies a signature against it,
 * exactly as it would against Canvas. Every claim check runs for real.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=8089",
                "lti.tool-base-url=http://localhost:8089",
                "lti.platforms[0].name=Stub platform",
                "lti.platforms[0].issuer=https://platform.test",
                "lti.platforms[0].client-id=test-client-1",
                "lti.platforms[0].deployment-ids[0]=dep-1",
                "lti.platforms[0].oidc-auth-url=https://platform.test/auth",
                "lti.platforms[0].jwks-url=http://localhost:8089/.well-known/jwks.json",
                "lti.platforms[0].token-url=http://localhost:8089/stub/token"
        })
// Identical configuration to AdvantageServiceTest, including this import, so both classes
// share one application context. Two contexts asking for the same fixed port would collide.
@Import(StubPlatform.class)
class LaunchFlowTest {

    private static final String ISSUER = "https://platform.test";
    private static final String CLIENT_ID = "test-client-1";
    private static final String DEPLOYMENT = "dep-1";

    @Autowired
    private ToolKeyService keys;

    @Autowired
    private StateService stateService;

    private final TestRestTemplate rest = new TestRestTemplate();

    @Test
    void validResourceLinkLaunchReachesTheTask() throws Exception {
        StateService.StateAndNonce sn = stateService.issue();
        String token = idToken(sn.nonce(), DEPLOYMENT, LtiClaims.MESSAGE_TYPE_RESOURCE_LINK);

        ResponseEntity<String> response = postLaunch(token, sn.state(), sn.state());

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody())
                .contains("Reading response: primary sources")       // the task_id resolved
                .contains("no separate login");                      // the student is signed in
    }

    @Test
    void deepLinkingRequestReachesThePicker() throws Exception {
        StateService.StateAndNonce sn = stateService.issue();
        String token = idToken(sn.nonce(), DEPLOYMENT, LtiClaims.MESSAGE_TYPE_DEEP_LINKING);

        ResponseEntity<String> response = postLaunch(token, sn.state(), sn.state());

        assertThat(response.getBody()).contains("Select content for your course");
    }

    @Test
    void aTokenCarryingSomeoneElsesNonceIsRejected() throws Exception {
        StateService.StateAndNonce mine = stateService.issue();
        StateService.StateAndNonce other = stateService.issue();

        // A perfectly valid, correctly signed, unexpired token — but its nonce belongs to a
        // different flow. This is what a captured-and-replayed launch looks like.
        String token = idToken(other.nonce(), DEPLOYMENT, LtiClaims.MESSAGE_TYPE_RESOURCE_LINK);
        ResponseEntity<String> response = postLaunch(token, mine.state(), mine.state());

        assertThat(response.getBody())
                .contains("Launch rejected")
                .contains("nonce does not match");
    }

    @Test
    void aStateCanOnlyBeRedeemedOnce() {
        StateService.StateAndNonce sn = stateService.issue();

        assertThat(stateService.redeem(sn.state())).contains(sn.nonce());
        assertThat(stateService.redeem(sn.state())).isEmpty();
    }

    @Test
    void aNonceCanOnlyBeSpentOnce() {
        String nonce = "nonce-" + System.nanoTime();

        assertThat(stateService.claimNonce(nonce)).isTrue();
        assertThat(stateService.claimNonce(nonce)).isFalse();
    }

    @Test
    void anUnknownDeploymentIsRejected() throws Exception {
        StateService.StateAndNonce sn = stateService.issue();
        String token = idToken(sn.nonce(), "dep-not-registered",
                LtiClaims.MESSAGE_TYPE_RESOURCE_LINK);

        ResponseEntity<String> response = postLaunch(token, sn.state(), sn.state());

        assertThat(response.getBody())
                .contains("Launch rejected")
                .contains("Unrecognised deployment_id");
    }

    @Test
    void aStateThatDoesNotMatchTheCookieIsRejected() throws Exception {
        StateService.StateAndNonce sn = stateService.issue();
        String token = idToken(sn.nonce(), DEPLOYMENT, LtiClaims.MESSAGE_TYPE_RESOURCE_LINK);

        ResponseEntity<String> response = postLaunch(token, sn.state(), "a-different-cookie");

        assertThat(response.getBody())
                .contains("Launch rejected")
                .contains("state parameter does not match");
    }

    @Test
    void theJwksEndpointPublishesOnlyPublicKeyMaterial() {
        ResponseEntity<String> response =
                rest.getForEntity("http://localhost:8089/.well-known/jwks.json", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"kty\":\"RSA\"").contains("\"kid\"");
        // A tool that leaks its private exponent can have its Deep Linking responses forged.
        assertThat(response.getBody()).doesNotContain("\"d\":");
    }

    private ResponseEntity<String> postLaunch(String idToken, String state, String cookieState) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("id_token", idToken);
        form.add("state", state);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.add(HttpHeaders.COOKIE, "lti_state=" + cookieState);

        return rest.postForEntity("http://localhost:8089/lti/launch",
                new HttpEntity<>(form, headers), String.class);
    }

    private String idToken(String nonce, String deploymentId, String messageType) throws Exception {
        Date now = new Date();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(CLIENT_ID)
                .subject("platform-user-42")
                .issueTime(now)
                .expirationTime(new Date(now.getTime() + 300_000L))
                .claim("nonce", nonce)
                .claim("name", "Alex Mercer")
                .claim(LtiClaims.VERSION, LtiClaims.LTI_VERSION)
                .claim(LtiClaims.MESSAGE_TYPE, messageType)
                .claim(LtiClaims.DEPLOYMENT_ID, deploymentId)
                .claim(LtiClaims.ROLES, List.of(LtiClaims.ROLE_INSTRUCTOR))
                .claim(LtiClaims.CONTEXT, Map.of("id", "course-7", "title", "Research Methods"))
                .claim(LtiClaims.RESOURCE_LINK, Map.of("id", "link-9", "title", "Week 3 task"))
                .claim(LtiClaims.CUSTOM, Map.of("task_id", "t-101"))
                .claim(LtiClaims.DEEP_LINKING_SETTINGS, Map.of(
                        "deep_link_return_url", "https://platform.test/deep_link_return",
                        "accept_types", List.of("ltiResourceLink"),
                        "data", "opaque-platform-data"))
                .build();

        RSAKey key = keys.signingKey();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
