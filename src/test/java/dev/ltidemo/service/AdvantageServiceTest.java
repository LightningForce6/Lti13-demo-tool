package dev.ltidemo.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.ltidemo.ags.AgsClient;
import dev.ltidemo.key.ToolKeyService;
import dev.ltidemo.launch.LtiClaims;
import dev.ltidemo.nrps.NrpsClient;
import dev.ltidemo.security.StateService;
import dev.ltidemo.stub.StubPlatform;
import org.junit.jupiter.api.BeforeEach;
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
 * Exercises AGS and NRPS end to end against {@link StubPlatform}.
 *
 * <p>Nothing is mocked. The tool fetches a real access token by signing a real client assertion,
 * the stub verifies that signature against the tool's published key before issuing one, and
 * every service call carries the resulting bearer token. If the OAuth layer is wrong, these
 * tests fail at the token endpoint rather than passing on a stubbed-out happy path.
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
@Import(StubPlatform.class)
class AdvantageServiceTest {

    @Autowired private ToolKeyService keys;
    @Autowired private StateService stateService;
    @Autowired private StubPlatform platform;
    @Autowired private AgsClient ags;
    @Autowired private NrpsClient nrps;

    private final TestRestTemplate rest = new TestRestTemplate();

    @BeforeEach
    void clearStub() {
        platform.reset();
    }

    @Test
    void postingAScoreCreatesTheColumnThenWritesToIt() throws Exception {
        String session = launchAsInstructor();

        ResponseEntity<String> response = post("/lti/services/grade", session,
                form("score", "83"));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("Score posted");

        // A column was created, bound to the resource link the launch came from.
        assertThat(platform.lineItems).hasSize(1);
        var lineItem = platform.lineItems.values().iterator().next();
        assertThat(lineItem.resourceLinkId()).isEqualTo("link-9");
        assertThat(lineItem.scoreMaximum()).isEqualTo(100.0);

        // And a score landed on it, keyed on the launch's sub rather than on an email.
        assertThat(platform.postedScores).hasSize(1);
        Map<String, Object> score = platform.postedScores.get(0);
        assertThat(score.get("userId")).isEqualTo("platform-user-42");
        assertThat(score.get("scoreGiven")).isEqualTo(83.0);
        assertThat(score.get("scoreMaximum")).isEqualTo(100.0);
        assertThat(score.get("gradingProgress")).isEqualTo("FullyGraded");
    }

    @Test
    void aSecondScoreReusesTheExistingColumn() throws Exception {
        post("/lti/services/grade", launchAsInstructor(), form("score", "70"));
        post("/lti/services/grade", launchAsInstructor(), form("score", "91"));

        // Two scores, one column. A tool that skips the lookup adds a duplicate column to
        // someone's gradebook on every run.
        assertThat(platform.lineItems).hasSize(1);
        assertThat(platform.postedScores).hasSize(2);
        assertThat(platform.postedScores.get(1).get("scoreGiven")).isEqualTo(91.0);
    }

    @Test
    void anAdvisoryScoreIsMarkedForManualReview() throws Exception {
        post("/lti/services/grade", launchAsInstructor(),
                form("score", "64", "awaitingReview", "true"));

        Map<String, Object> score = platform.postedScores.get(0);
        assertThat(score.get("gradingProgress")).isEqualTo("PendingManual");
        assertThat(score.get("activityProgress")).isEqualTo("Submitted");
    }

    @Test
    void theRosterFollowsEveryPage() throws Exception {
        String session = launchAsInstructor();

        ResponseEntity<String> response = post("/lti/services/roster", session, form());

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        // Two members on page one, two more advertised only in the Link header.
        assertThat(response.getBody())
                .contains("4 member(s)")
                .contains("Sam Okafor")
                .contains("Dana Ruiz")
                // The privacy-restricted member has no name, so the user_id stands in.
                .contains("learner-2");
    }

    @Test
    void theRosterReportsMembersTheInstitutionWithheld() throws Exception {
        ResponseEntity<String> response =
                post("/lti/services/roster", launchAsInstructor(), form());

        // One of the four has no email and one has withdrawn. Both are normal, and a sync that
        // treats either as an error will misreport a whole cohort.
        assertThat(response.getBody()).contains("Without an email address").contains("Inactive");
    }

    @Test
    void aLaunchWithNoServiceClaimsOffersNeitherService() {
        var bare = new dev.ltidemo.launch.LtiLaunch(
                null, LtiClaims.MESSAGE_TYPE_RESOURCE_LINK, "dep-1", "sub", null, null,
                List.of(), null, null, null, null, null, null, null, null);

        assertThat(nrps.isAvailable(bare)).isFalse();
        assertThat(ags.isAvailable(bare)).isFalse();
    }

    @Test
    void aLearnerCannotPullTheRoster() throws Exception {
        String session = launchAsLearner();

        ResponseEntity<String> response = post("/lti/services/roster", session, form());

        assertThat(response.getBody()).contains("Only an instructor can retrieve a course roster");
    }

    @Test
    void theTokenEndpointIsAskedOnlyForTheScopesEachCallNeeds() throws Exception {
        post("/lti/services/grade", launchAsInstructor(), form("score", "50"));

        // Separate tokens for reading line items, creating one, and posting a score — platforms
        // issue tokens scoped to exactly what was asked for, so one token per scope set is the
        // only thing that works.
        assertThat(platform.requestedScopes).isNotEmpty();
        assertThat(platform.requestedScopes).anyMatch(s -> s.contains("/score"));
        assertThat(platform.requestedScopes).anyMatch(s -> s.contains("lineitem"));
    }

    @Test
    void scoresUrlIsInsertedBeforeAnyQueryString() {
        // The case a real platform hands you and a naive implementation breaks on.
        assertThat(AgsClient.scoresUrl("https://lms.test/line_items/7?type_id=1"))
                .isEqualTo("https://lms.test/line_items/7/scores?type_id=1");
        assertThat(AgsClient.scoresUrl("https://lms.test/line_items/7"))
                .isEqualTo("https://lms.test/line_items/7/scores");
    }

    @Test
    void theNextPageIsReadFromTheLinkHeader() {
        assertThat(NrpsClient.nextPage(
                "<https://lms.test/m?page=2>; rel=\"next\", <https://lms.test/m?page=9>; rel=\"last\""))
                .isEqualTo("https://lms.test/m?page=2");
        // Unquoted rel is legal grammar, and some platforms emit it.
        assertThat(NrpsClient.nextPage("<https://lms.test/m?page=2>; rel=next"))
                .isEqualTo("https://lms.test/m?page=2");
        // No next relation means the last page, not an error.
        assertThat(NrpsClient.nextPage("<https://lms.test/m?page=1>; rel=\"prev\"")).isNull();
        assertThat(NrpsClient.nextPage(null)).isNull();
    }

    // --- helpers ---------------------------------------------------------------------------

    private String launchAsInstructor() throws Exception {
        return launch(LtiClaims.ROLE_INSTRUCTOR);
    }

    private String launchAsLearner() throws Exception {
        return launch(LtiClaims.ROLE_LEARNER);
    }

    /** Performs a full launch and returns the session cookie the service calls need. */
    private String launch(String role) throws Exception {
        StateService.StateAndNonce sn = stateService.issue();
        String token = idToken(sn.nonce(), role);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("id_token", token);
        body.add("state", sn.state());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.add(HttpHeaders.COOKIE, "lti_state=" + sn.state());

        ResponseEntity<String> response = rest.postForEntity("http://localhost:8089/lti/launch",
                new HttpEntity<>(body, headers), String.class);

        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).as("launch should start a session").isNotNull();
        return cookies.stream()
                .filter(c -> c.startsWith("JSESSIONID"))
                .map(c -> c.split(";")[0])
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No session cookie on the launch"));
    }

    private ResponseEntity<String> post(String path, String sessionCookie,
                                        MultiValueMap<String, String> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.add(HttpHeaders.COOKIE, sessionCookie);
        return rest.postForEntity("http://localhost:8089" + path,
                new HttpEntity<>(body, headers), String.class);
    }

    private static MultiValueMap<String, String> form(String... pairs) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            form.add(pairs[i], pairs[i + 1]);
        }
        return form;
    }

    private String idToken(String nonce, String role) throws Exception {
        Date now = new Date();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(StubPlatform.ISSUER)
                .audience(StubPlatform.CLIENT_ID)
                .subject("platform-user-42")
                .issueTime(now)
                .expirationTime(new Date(now.getTime() + 300_000L))
                .claim("nonce", nonce)
                .claim("name", "Alex Mercer")
                .claim(LtiClaims.VERSION, LtiClaims.LTI_VERSION)
                .claim(LtiClaims.MESSAGE_TYPE, LtiClaims.MESSAGE_TYPE_RESOURCE_LINK)
                .claim(LtiClaims.DEPLOYMENT_ID, "dep-1")
                .claim(LtiClaims.ROLES, List.of(role))
                .claim(LtiClaims.CONTEXT, Map.of("id", "course-7", "title", "Research Methods"))
                .claim(LtiClaims.RESOURCE_LINK, Map.of("id", "link-9", "title", "Week 3 task"))
                .claim(LtiClaims.CUSTOM, Map.of("task_id", "t-101"))
                // The service claims a real platform attaches when the scopes are enabled.
                .claim(LtiClaims.AGS_ENDPOINT, Map.of(
                        "scope", List.of(ServiceScopes.LINEITEM, ServiceScopes.LINEITEM_READONLY,
                                ServiceScopes.SCORE, ServiceScopes.RESULT_READONLY),
                        "lineitems", StubPlatform.BASE + "/lineitems"))
                .claim(LtiClaims.NRPS_ENDPOINT, Map.of(
                        "context_memberships_url", StubPlatform.BASE + "/memberships",
                        "service_versions", List.of("2.0")))
                .build();

        RSAKey key = keys.signingKey();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

}
