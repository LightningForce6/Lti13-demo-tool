package dev.ltidemo.web;

import dev.ltidemo.launch.LaunchValidationException;
import dev.ltidemo.service.ServiceCallException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Turns a rejected launch into a readable page instead of a stack trace.
 *
 * <p>Applied across every controller, not just the launch endpoint: login initiation can fail
 * for an unregistered issuer, and the Deep Linking return can fail on a launch with no
 * settings claim. Both deserve the same treatment.
 *
 * <p>The status is 400 rather than 500 — a launch the tool correctly refuses is a bad request,
 * and getting that right keeps genuine server faults visible in monitoring.
 */
@ControllerAdvice
public class LaunchErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(LaunchErrorHandler.class);

    @ExceptionHandler(LaunchValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String onInvalidLaunch(LaunchValidationException ex, Model model) {
        log.warn("Rejected launch: {}", ex.getMessage());
        model.addAttribute("message", ex.getMessage());
        model.addAttribute("cause", ex.getCause() == null ? null : ex.getCause().getMessage());
        return "launch-error";
    }

    @ExceptionHandler(ServiceCallException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public String onServiceFailure(ServiceCallException ex, Model model) {
        log.warn("Service call failed: {}", ex.getMessage());
        model.addAttribute("message", ex.getMessage());
        model.addAttribute("cause", ex.getCause() == null ? null : ex.getCause().getMessage());
        return "launch-error";
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String onIllegalState(IllegalStateException ex, Model model) {
        log.warn("Launch could not be completed: {}", ex.getMessage());
        model.addAttribute("message", ex.getMessage());
        model.addAttribute("cause", null);
        return "launch-error";
    }
}
