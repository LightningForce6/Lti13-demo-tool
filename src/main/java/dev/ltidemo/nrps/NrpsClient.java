package dev.ltidemo.nrps;

import dev.ltidemo.launch.LtiLaunch;
import dev.ltidemo.service.PlatformTokenService;
import dev.ltidemo.service.ServiceCallException;
import dev.ltidemo.service.ServiceScopes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Names and Role Provisioning Services: course rosters.
 *
 * <p>Like AGS, the endpoint arrives in the launch rather than from configuration.
 *
 * <p>The part that catches people out is pagination. Large courses come back in pages, and the
 * next page is advertised in an HTTP {@code Link} header — not in the JSON body, where anyone
 * would look first. A tool that reads only the body gets the first page, no error, and a
 * roster that is quietly wrong for any course above the platform's page size. That failure is
 * invisible in a test course of four students and obvious in a cohort of three hundred.
 */
@Service
public class NrpsClient {

    private static final Logger log = LoggerFactory.getLogger(NrpsClient.class);

    private static final String MEMBERSHIP_MEDIA_TYPE =
            "application/vnd.ims.lti-nrps.v2.membershipcontainer+json";

    /** Safety net: a malformed Link header should not become an infinite loop. */
    private static final int MAX_PAGES = 50;

    private final PlatformTokenService tokens;
    private final RestClient http = RestClient.create();

    public NrpsClient(PlatformTokenService tokens) {
        this.tokens = tokens;
    }

    public boolean isAvailable(LtiLaunch launch) {
        Map<String, Object> claim = launch.nrpsEndpoint();
        return claim != null && claim.get("context_memberships_url") != null;
    }

    /** The whole roster for the course this launch came from, following every page. */
    public List<Member> courseRoster(LtiLaunch launch) {
        return fetch(launch, membershipsUrl(launch));
    }

    /**
     * Only the members associated with this specific resource link.
     *
     * <p>Useful when a tool is attached to one assignment that not everyone in the course can
     * see — a section-restricted assignment, say. Not every platform supports the filter; those
     * that do not return the full course roster, which is a widening rather than a failure, so
     * callers that need the distinction have to check what came back.
     */
    public List<Member> resourceLinkRoster(LtiLaunch launch) {
        String url = membershipsUrl(launch);
        if (launch.resourceLinkId() != null) {
            url = url + (url.contains("?") ? "&" : "?") + "rlid=" + launch.resourceLinkId();
        }
        return fetch(launch, url);
    }

    private List<Member> fetch(LtiLaunch launch, String startUrl) {
        String bearer = "Bearer " + tokens.accessToken(launch.platform(),
                List.of(ServiceScopes.CONTEXT_MEMBERSHIP_READONLY));

        List<Member> all = new ArrayList<>();
        String url = startUrl;
        int page = 0;

        while (url != null && page < MAX_PAGES) {
            ResponseEntity<MembershipContainer> response = http.get()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .accept(MediaType.parseMediaType(MEMBERSHIP_MEDIA_TYPE))
                    .retrieve()
                    .toEntity(MembershipContainer.class);

            MembershipContainer body = response.getBody();
            if (body != null && body.members() != null) {
                all.addAll(body.members());
            }
            url = nextPage(response.getHeaders().getFirst(HttpHeaders.LINK));
            page++;
        }

        if (page >= MAX_PAGES) {
            log.warn("Stopped after {} pages of roster — check the platform's Link header", MAX_PAGES);
        }
        log.info("Fetched {} roster member(s) across {} page(s)", all.size(), page);
        return all;
    }

    /**
     * Pulls the {@code rel="next"} URL out of a Link header.
     *
     * <p>The header can hold several relations, in either order, with or without spaces:
     * {@code <https://…?page=2>; rel="next", <https://…?page=9>; rel="last"}. Quotes around the
     * rel value are optional in the grammar, so both forms are accepted here.
     */
    static String nextPage(String linkHeader) {
        if (linkHeader == null || linkHeader.isBlank()) {
            return null;
        }
        for (String part : linkHeader.split(",")) {
            String[] segments = part.split(";");
            if (segments.length < 2) {
                continue;
            }
            String url = segments[0].trim();
            if (!url.startsWith("<") || !url.endsWith(">")) {
                continue;
            }
            for (int i = 1; i < segments.length; i++) {
                String rel = segments[i].trim().replace("\"", "").replace(" ", "");
                if (rel.equalsIgnoreCase("rel=next")) {
                    return url.substring(1, url.length() - 1);
                }
            }
        }
        return null;
    }

    private String membershipsUrl(LtiLaunch launch) {
        Map<String, Object> claim = launch.nrpsEndpoint();
        if (claim == null || claim.get("context_memberships_url") == null) {
            throw new ServiceCallException(
                    "This launch carried no NRPS claim, so the platform has not granted this tool "
                    + "roster access for this course.");
        }
        return String.valueOf(claim.get("context_memberships_url"));
    }
}
