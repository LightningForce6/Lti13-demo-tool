package dev.ltidemo.nrps;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/** One page of a course roster, as returned by the memberships endpoint. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MembershipContainer(
        @JsonProperty("id") String id,
        @JsonProperty("context") Map<String, Object> context,
        @JsonProperty("members") List<Member> members
) {

    public String contextTitle() {
        Object title = context == null ? null : context.get("title");
        return title == null ? null : String.valueOf(title);
    }
}
