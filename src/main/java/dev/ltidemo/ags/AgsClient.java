package dev.ltidemo.ags;

import dev.ltidemo.launch.LtiLaunch;
import dev.ltidemo.service.PlatformTokenService;
import dev.ltidemo.service.ServiceCallException;
import dev.ltidemo.service.ServiceScopes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Assignment and Grade Services: gradebook columns and scores.
 *
 * <p>The endpoints are not configured anywhere. They arrive in the launch, inside the AGS claim,
 * and they are specific to that course and that resource link. This is the practical reason the
 * launch has to capture them: there is no other way to learn them, and a tool that did not store
 * them at launch has to wait for the next one before it can write a grade.
 *
 * <p>The claim carries {@code lineitems} (the collection for the course) and sometimes
 * {@code lineitem} (the one column bound to this launch, present when the instructor attached
 * the tool to a graded item). When {@code lineitem} is there, use it — creating a second column
 * for an assignment that already has one is a visible mess in someone's gradebook.
 */
@Service
public class AgsClient {

    private static final Logger log = LoggerFactory.getLogger(AgsClient.class);

    private static final String LINEITEM_MEDIA_TYPE = "application/vnd.ims.lis.v2.lineitem+json";
    private static final String LINEITEM_CONTAINER_MEDIA_TYPE =
            "application/vnd.ims.lis.v2.lineitemcontainer+json";
    private static final String SCORE_MEDIA_TYPE = "application/vnd.ims.lis.v1.score+json";

    private final PlatformTokenService tokens;
    private final RestClient http = RestClient.create();

    public AgsClient(PlatformTokenService tokens) {
        this.tokens = tokens;
    }

    public boolean isAvailable(LtiLaunch launch) {
        Map<String, Object> claim = launch.agsEndpoint();
        return claim != null && claim.get("lineitems") != null;
    }

    /** Scopes the platform actually granted for this launch, as declared in the AGS claim. */
    public List<String> grantedScopes(LtiLaunch launch) {
        Map<String, Object> claim = launch.agsEndpoint();
        if (claim == null) {
            return List.of();
        }
        Object scope = claim.get("scope");
        if (scope instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    /**
     * Finds the column for this launch, or creates one.
     *
     * <p>Order matters. The bound {@code lineitem} first, because the platform has already told
     * us which column belongs to this assignment. Then a search of the collection by
     * {@code resourceLinkId}, which catches the case where a previous run created one. Only then
     * a create. Skipping the search is how tools end up adding a duplicate column on every
     * deployment.
     */
    public LineItem findOrCreateLineItem(LtiLaunch launch, String label, double scoreMaximum,
                                         String resourceId) {
        Map<String, Object> claim = requireClaim(launch);

        Object bound = claim.get("lineitem");
        if (bound != null) {
            String url = String.valueOf(bound);
            log.debug("Launch is bound to line item {}", url);
            return readLineItem(launch, url);
        }

        String lineItemsUrl = String.valueOf(claim.get("lineitems"));
        Optional<LineItem> existing = findByResourceLink(launch, lineItemsUrl,
                launch.resourceLinkId());
        if (existing.isPresent()) {
            log.debug("Reusing existing line item {}", existing.get().id());
            return existing.get();
        }

        LineItem created = http.post()
                .uri(lineItemsUrl)
                .header(HttpHeaders.AUTHORIZATION, bearer(launch, List.of(ServiceScopes.LINEITEM)))
                .contentType(MediaType.parseMediaType(LINEITEM_MEDIA_TYPE))
                .accept(MediaType.parseMediaType(LINEITEM_MEDIA_TYPE))
                .body(LineItem.toCreate(label, scoreMaximum, resourceId,
                        launch.resourceLinkId(), "ltidemo"))
                .retrieve()
                .body(LineItem.class);

        if (created == null || created.id() == null) {
            throw new ServiceCallException("Platform accepted the line item but returned no id.");
        }
        log.info("Created line item {} in course {}", created.id(), launch.contextId());
        return created;
    }

    public LineItem readLineItem(LtiLaunch launch, String lineItemUrl) {
        LineItem item = http.get()
                .uri(lineItemUrl)
                .header(HttpHeaders.AUTHORIZATION,
                        bearer(launch, List.of(ServiceScopes.LINEITEM_READONLY)))
                .accept(MediaType.parseMediaType(LINEITEM_MEDIA_TYPE))
                .retrieve()
                .body(LineItem.class);
        if (item == null) {
            throw new ServiceCallException("No line item at " + lineItemUrl);
        }
        return item;
    }

    public Optional<LineItem> findByResourceLink(LtiLaunch launch, String lineItemsUrl,
                                                 String resourceLinkId) {
        if (resourceLinkId == null) {
            return Optional.empty();
        }
        String uri = appendQuery(lineItemsUrl, "resource_link_id=" + resourceLinkId);

        LineItem[] items = http.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION,
                        bearer(launch, List.of(ServiceScopes.LINEITEM_READONLY)))
                .accept(MediaType.parseMediaType(LINEITEM_CONTAINER_MEDIA_TYPE))
                .retrieve()
                .body(LineItem[].class);

        return items == null || items.length == 0 ? Optional.empty() : Optional.of(items[0]);
    }

    /**
     * Posts a score to a column.
     *
     * <p>Note {@link #scoresUrl}: the scores endpoint is the line item URL with {@code /scores}
     * appended — but a platform is free to hand out line item URLs that already carry a query
     * string, and Canvas does. Appending naively gives
     * {@code .../line_items/7?type_id=1/scores}, which 404s in a way that looks like a
     * permissions problem. Inserting before the query is the fix, and it is the kind of detail
     * that only shows up against a real instance.
     */
    public void postScore(LtiLaunch launch, LineItem lineItem, Score score) {
        if (lineItem.id() == null) {
            throw new ServiceCallException("Cannot post a score to a line item with no id.");
        }
        // Match the column's own maximum, or platforms disagree about what the number means.
        Double maximum = lineItem.scoreMaximum() != null
                ? lineItem.scoreMaximum()
                : score.scoreMaximum();
        Score toSend = new Score(score.userId(), score.scoreGiven(), maximum,
                score.activityProgress(), score.gradingProgress(), score.timestamp(),
                score.comment());

        http.post()
                .uri(scoresUrl(lineItem.id()))
                .header(HttpHeaders.AUTHORIZATION, bearer(launch, List.of(ServiceScopes.SCORE)))
                .contentType(MediaType.parseMediaType(SCORE_MEDIA_TYPE))
                .body(toSend)
                .retrieve()
                .toBodilessEntity();

        log.info("Posted score {}/{} for {} to {}", toSend.scoreGiven(), maximum,
                score.userId(), lineItem.id());
    }

    static String scoresUrl(String lineItemUrl) {
        int q = lineItemUrl.indexOf('?');
        if (q < 0) {
            return lineItemUrl + "/scores";
        }
        return lineItemUrl.substring(0, q) + "/scores" + lineItemUrl.substring(q);
    }

    static String appendQuery(String url, String query) {
        return url + (url.contains("?") ? "&" : "?") + query;
    }

    private Map<String, Object> requireClaim(LtiLaunch launch) {
        Map<String, Object> claim = launch.agsEndpoint();
        if (claim == null || claim.get("lineitems") == null) {
            throw new ServiceCallException(
                    "This launch carried no AGS endpoint claim, so the platform has not granted "
                    + "this tool gradebook access for this course.");
        }
        return claim;
    }

    private String bearer(LtiLaunch launch, List<String> scopes) {
        List<String> granted = grantedScopes(launch);
        if (!granted.isEmpty()) {
            for (String needed : scopes) {
                if (!granted.contains(needed)) {
                    throw new ServiceCallException("The launch did not grant " + needed
                            + ". Granted scopes: " + granted);
                }
            }
        }
        return "Bearer " + tokens.accessToken(launch.platform(), scopes);
    }

    /** Allowed values, kept here so callers do not have to remember the spelling. */
    public static final List<String> ACTIVITY_PROGRESS = Arrays.asList(
            "Initialized", "Started", "InProgress", "Submitted", "Completed");

    public static final List<String> GRADING_PROGRESS = Arrays.asList(
            "FullyGraded", "Pending", "PendingManual", "Failed", "NotReady");
}
