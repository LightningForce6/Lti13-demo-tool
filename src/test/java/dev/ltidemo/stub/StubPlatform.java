package dev.ltidemo.stub;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import dev.ltidemo.ags.LineItem;
import dev.ltidemo.key.ToolKeyService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fake LMS: token endpoint, AGS line items and scores, and a paginated NRPS roster.
 *
 * <p>It is strict on purpose. The token endpoint verifies the client assertion's signature
 * against the tool's own public key and checks its issuer, subject and audience, and every
 * service endpoint requires the bearer token that came back. A stub that accepted anything
 * would let a broken OAuth implementation pass its tests, which is the opposite of useful.
 *
 * <p>The roster is deliberately served in two pages, with the second advertised only in a
 * {@code Link} header — the detail a client that reads just the response body silently gets
 * wrong.
 */
@RestController
public class StubPlatform {

    public static final String ISSUER = "https://platform.test";
    public static final String CLIENT_ID = "test-client-1";
    public static final String BASE = "http://localhost:8089/stub";
    public static final String ACCESS_TOKEN = "stub-access-token";

    private final ToolKeyService keys;
    private final AtomicInteger nextLineItemId = new AtomicInteger(1);

    /** Line items the tool has created, by id. */
    public final Map<String, LineItem> lineItems = new LinkedHashMap<>();

    /** Every score the tool has posted, in order. */
    public final List<Map<String, Object>> postedScores = new ArrayList<>();

    /** Scopes requested at the token endpoint, in order. */
    public final List<String> requestedScopes = new ArrayList<>();

    public StubPlatform(ToolKeyService keys) {
        this.keys = keys;
    }

    public void reset() {
        lineItems.clear();
        postedScores.clear();
        requestedScopes.clear();
        nextLineItemId.set(1);
    }

    @PostMapping(value = "/stub/token", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> token(@RequestParam("grant_type") String grantType,
                                     @RequestParam("client_assertion_type") String assertionType,
                                     @RequestParam("client_assertion") String assertion,
                                     @RequestParam("scope") String scope) {

        if (!"client_credentials".equals(grantType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported_grant_type");
        }
        if (!"urn:ietf:params:oauth:client-assertion-type:jwt-bearer".equals(assertionType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_request");
        }

        try {
            SignedJWT jwt = SignedJWT.parse(assertion);
            if (!jwt.verify(new RSASSAVerifier(keys.signingKey().toRSAPublicKey()))) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_client");
            }
            var claims = jwt.getJWTClaimsSet();
            if (!CLIENT_ID.equals(claims.getIssuer()) || !CLIENT_ID.equals(claims.getSubject())) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "invalid_client: iss and sub must both be the client_id");
            }
            if (claims.getAudience() == null
                    || !claims.getAudience().contains(BASE + "/token")) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "invalid_client: wrong aud");
            }
            if (claims.getJWTID() == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "invalid_client: assertion needs a jti");
            }
        } catch (java.text.ParseException | com.nimbusds.jose.JOSEException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_client", e);
        }

        requestedScopes.add(scope);
        return Map.of("access_token", ACCESS_TOKEN, "token_type", "Bearer", "expires_in", 3600,
                "scope", scope);
    }

    @GetMapping(value = "/stub/lineitems", produces = "application/vnd.ims.lis.v2.lineitemcontainer+json")
    public List<LineItem> listLineItems(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                        @RequestParam(value = "resource_link_id", required = false)
                                        String resourceLinkId) {
        requireBearer(auth);
        return lineItems.values().stream()
                .filter(li -> resourceLinkId == null
                        || resourceLinkId.equals(li.resourceLinkId()))
                .toList();
    }

    @PostMapping(value = "/stub/lineitems", produces = "application/vnd.ims.lis.v2.lineitem+json")
    public LineItem createLineItem(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                   @RequestBody LineItem request) {
        requireBearer(auth);
        // A real platform hands back a URL, and often one carrying a query string — which is
        // exactly the case that breaks a naive scores URL.
        String id = BASE + "/lineitems/" + nextLineItemId.getAndIncrement() + "?type_id=1";
        LineItem stored = new LineItem(id, request.scoreMaximum(), request.label(),
                request.resourceId(), request.resourceLinkId(), request.tag());
        lineItems.put(id, stored);
        return stored;
    }

    @PostMapping("/stub/lineitems/{id}/scores")
    public ResponseEntity<Void> postScore(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                          @PathVariable("id") String id,
                                          @RequestBody Map<String, Object> score) {
        requireBearer(auth);
        Map<String, Object> record = new LinkedHashMap<>(score);
        record.put("lineItemId", id);
        postedScores.add(record);
        return ResponseEntity.ok().build();
    }

    @GetMapping(value = "/stub/memberships",
            produces = "application/vnd.ims.lti-nrps.v2.membershipcontainer+json")
    public ResponseEntity<Map<String, Object>> memberships(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "rlid", required = false) String rlid) {

        requireBearer(auth);

        List<Map<String, Object>> members = page == 1
                ? List.of(
                        member("platform-user-42", "Alex Mercer", "alex@example.edu",
                                "Instructor", "Active"),
                        member("learner-1", "Sam Okafor", "sam@example.edu", "Learner", "Active"))
                // Page two: one member with no name and no email, which is what a privacy-
                // restricted institution actually returns, and one who has withdrawn.
                : List.of(
                        member("learner-2", null, null, "Learner", "Active"),
                        member("learner-3", "Dana Ruiz", "dana@example.edu", "Learner",
                                "Inactive"));

        Map<String, Object> body = Map.of(
                "id", BASE + "/memberships",
                "context", Map.of("id", "course-7", "title", "Research Methods"),
                "members", members);

        HttpHeaders headers = new HttpHeaders();
        if (page == 1) {
            String next = BASE + "/memberships?page=2" + (rlid == null ? "" : "&rlid=" + rlid);
            headers.add(HttpHeaders.LINK,
                    "<" + next + ">; rel=\"next\", <" + next + ">; rel=\"last\"");
        }
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    private static Map<String, Object> member(String userId, String name, String email,
                                              String role, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user_id", userId);
        m.put("roles", List.of("http://purl.imsglobal.org/vocab/lis/v2/membership#" + role));
        m.put("status", status);
        if (name != null) {
            m.put("name", name);
        }
        if (email != null) {
            m.put("email", email);
        }
        return m;
    }

    private static void requireBearer(String auth) {
        if (auth == null || !auth.equals("Bearer " + ACCESS_TOKEN)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "bad bearer token");
        }
    }
}
