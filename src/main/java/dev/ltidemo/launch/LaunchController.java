package dev.ltidemo.launch;

import dev.ltidemo.task.Task;
import dev.ltidemo.task.TaskRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;

/**
 * Step two of the launch: the platform posts the signed id_token here.
 *
 * <p>Once validated, the message type decides what happens. A Deep Linking request means an
 * instructor is choosing content, so we show the picker. A resource link request means someone
 * is opening content that was chosen earlier, so we show the task.
 *
 * <p>This is the whole of the branch, and it is worth noticing how small it is. The work in an
 * LTI integration is not routing; it is everything {@link IdTokenValidator} does before this
 * method gets a launch it is allowed to believe.
 */
@Controller
public class LaunchController {

    private static final Logger log = LoggerFactory.getLogger(LaunchController.class);

    public static final String SESSION_LAUNCH = "lti.launch";

    private final IdTokenValidator validator;
    private final TaskRepository tasks;

    public LaunchController(IdTokenValidator validator, TaskRepository tasks) {
        this.validator = validator;
        this.tasks = tasks;
    }

    @PostMapping("/lti/launch")
    public String launch(@RequestParam(value = "id_token", required = false) String idToken,
                         @RequestParam(value = "state", required = false) String state,
                         HttpServletRequest request,
                         HttpSession session,
                         Model model) {

        String stateCookie = readCookie(request, OidcLoginController.STATE_COOKIE);
        LtiLaunch launch = validator.validate(idToken, state, stateCookie);

        // Held for the duration of the browser session so the Deep Linking submit, which
        // arrives as an ordinary form post with no token of its own, can be tied back to a
        // launch we actually verified.
        session.setAttribute(SESSION_LAUNCH, launch);

        if (launch.isDeepLinkingRequest()) {
            model.addAttribute("launch", launch);
            model.addAttribute("tasks", tasks.findAll());
            return "picker";
        }

        Optional<Task> task = Optional.ofNullable(launch.taskId()).flatMap(tasks::findById);
        model.addAttribute("launch", launch);
        model.addAttribute("task", task.orElse(null));

        log.info("Resource link launch: context='{}' task={} user={}",
                launch.contextTitle(), launch.taskId(), launch.stableUserKey());
        return "task";
    }

    private static String readCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (var c : request.getCookies()) {
            if (c.getName().equals(name)) {
                return c.getValue();
            }
        }
        return null;
    }
}
