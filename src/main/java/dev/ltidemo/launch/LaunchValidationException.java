package dev.ltidemo.launch;

/**
 * A launch that could not be trusted.
 *
 * <p>The messages are deliberately specific — "unrecognised deployment_id", not "invalid
 * launch". When a university's IT department emails to say the integration is broken, the
 * difference between those two messages is a five-minute fix and a two-day investigation.
 */
public class LaunchValidationException extends RuntimeException {

    public LaunchValidationException(String message) {
        super(message);
    }

    public LaunchValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
