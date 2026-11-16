package dev.ltidemo.ags;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A gradebook column.
 *
 * <p>{@code resourceLinkId} is what ties a column to the assignment the launch came from. Set
 * it and the platform associates the column with that assignment; omit it and you get a
 * free-floating column, which is occasionally what you want and usually a bug.
 *
 * <p>{@code resourceId} is the tool's own identifier for what the column measures. It is the
 * field that lets you find your column again on a later launch without storing platform URLs,
 * so it is worth populating even though nothing requires it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LineItem(
        @JsonProperty("id") String id,
        @JsonProperty("scoreMaximum") Double scoreMaximum,
        @JsonProperty("label") String label,
        @JsonProperty("resourceId") String resourceId,
        @JsonProperty("resourceLinkId") String resourceLinkId,
        @JsonProperty("tag") String tag
) {

    public static LineItem toCreate(String label, double scoreMaximum, String resourceId,
                                    String resourceLinkId, String tag) {
        return new LineItem(null, scoreMaximum, label, resourceId, resourceLinkId, tag);
    }
}
