package dev.ltidemo.deeplink;

import dev.ltidemo.launch.LaunchController;
import dev.ltidemo.launch.LtiLaunch;
import dev.ltidemo.task.Task;
import dev.ltidemo.task.TaskRepository;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Receives the instructor's selection from the picker and returns it to the LMS.
 *
 * <p>The response is delivered by auto-submitting a form from the browser rather than by a
 * server-to-server call. That looks odd until you notice the launch was a browser POST too:
 * the whole protocol is a conversation between the LMS and the tool conducted through the
 * instructor's browser, which is exactly why the signed JWT matters. Nothing in transit is
 * trusted; only the signature is.
 */
@Controller
public class DeepLinkingController {

    private static final Logger log = LoggerFactory.getLogger(DeepLinkingController.class);

    private final DeepLinkingResponseBuilder builder;
    private final TaskRepository tasks;

    public DeepLinkingController(DeepLinkingResponseBuilder builder, TaskRepository tasks) {
        this.builder = builder;
        this.tasks = tasks;
    }

    @PostMapping("/lti/deeplink/select")
    public String select(@RequestParam(value = "taskIds", required = false) List<String> taskIds,
                         HttpSession session,
                         Model model) {

        LtiLaunch launch = (LtiLaunch) session.getAttribute(LaunchController.SESSION_LAUNCH);
        if (launch == null) {
            model.addAttribute("message",
                    "No launch in session. Start again from the LMS — this endpoint cannot be "
                    + "reached directly.");
            return "launch-error";
        }
        if (!launch.isDeepLinkingRequest()) {
            model.addAttribute("message", "This session is not a Deep Linking request.");
            return "launch-error";
        }
        // Role check: only an instructor may attach content to a course. The roles claim is
        // signed by the platform, so this is a real authorisation decision and not a UI hint.
        if (!launch.isInstructor()) {
            model.addAttribute("message", "Only an instructor can select content for a course.");
            return "launch-error";
        }

        List<Task> selected = (taskIds == null ? List.<String>of() : taskIds).stream()
                .map(tasks::findById)
                .flatMap(java.util.Optional::stream)
                .toList();

        String jwt = builder.build(launch, selected);
        String returnUrl = builder.returnUrl(launch);

        log.info("Returning {} content item(s) to {}", selected.size(), returnUrl);

        model.addAttribute("returnUrl", returnUrl);
        model.addAttribute("jwt", jwt);
        model.addAttribute("count", selected.size());
        return "deeplink-return";
    }
}
