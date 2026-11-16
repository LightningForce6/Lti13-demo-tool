package dev.ltidemo.ags;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * A score posted back to a gradebook column.
 *
 * <p>Three fields decide how this shows up to an instructor, and they are the ones worth
 * understanding before writing any of this:
 *
 * <ul>
 *   <li>{@code activityProgress} — how far the student got: Initialized, Started, InProgress,
 *       Submitted, Completed.
 *   <li>{@code gradingProgress} — whether the number is final: FullyGraded, Pending,
 *       PendingManual, Failed, NotReady. {@code PendingManual} is the useful one for a tool that
 *       produces advisory output: it posts the signal and tells the LMS a human still decides.
 *   <li>{@code scoreMaximum} — must match the line item's own maximum. Post 1.0 out of 1.0
 *       against a column whose maximum is 100 and platforms differ on whether they scale it,
 *       reject it, or record a 1%.
 * </ul>
 *
 * <p>Omitting {@code scoreGiven} entirely is legal and meaningful: it clears any existing score
 * while leaving the progress fields in place.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Score(
        @JsonProperty("userId") String userId,
        @JsonProperty("scoreGiven") Double scoreGiven,
        @JsonProperty("scoreMaximum") Double scoreMaximum,
        @JsonProperty("activityProgress") String activityProgress,
        @JsonProperty("gradingProgress") String gradingProgress,
        @JsonProperty("timestamp") String timestamp,
        @JsonProperty("comment") String comment
) {

    public static Score graded(String userId, double given, double maximum, String comment) {
        return new Score(userId, given, maximum, "Completed", "FullyGraded",
                Instant.now().toString(), comment);
    }

    /** A posted signal with the decision left to the instructor. */
    public static Score awaitingReview(String userId, double given, double maximum, String comment) {
        return new Score(userId, given, maximum, "Submitted", "PendingManual",
                Instant.now().toString(), comment);
    }
}
