package dev.ltidemo.web;

import dev.ltidemo.ags.AgsClient;
import dev.ltidemo.ags.LineItem;
import dev.ltidemo.ags.Score;
import dev.ltidemo.launch.LaunchController;
import dev.ltidemo.launch.LtiLaunch;
import dev.ltidemo.nrps.Member;
import dev.ltidemo.nrps.NrpsClient;
import dev.ltidemo.service.ServiceCallException;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Exercises AGS and NRPS from a validated launch, so both can be demonstrated against a real
 * LMS rather than only described.
 *
 * <p>The authorisation check is worth noting. A score may be posted for the launching user,
 * because the launch proves who they are. Pulling a roster is an instructor action: the data is
 * other people's, and a learner launching the tool has no business retrieving their classmates.
 * The roles claim is signed by the platform, so this is a real decision and not a UI nicety.
 */
@Controller
public class ServiceController {

    private final AgsClient ags;
    private final NrpsClient nrps;

    public ServiceController(AgsClient ags, NrpsClient nrps) {
        this.ags = ags;
        this.nrps = nrps;
    }

    @PostMapping("/lti/services/grade")
    public String postGrade(@RequestParam(defaultValue = "83") double score,
                            @RequestParam(defaultValue = "false") boolean awaitingReview,
                            HttpSession session,
                            Model model) {

        LtiLaunch launch = launchFrom(session);
        if (!ags.isAvailable(launch)) {
            throw new ServiceCallException(
                    "This launch carried no AGS claim. In most platforms that means the tool was "
                    + "not attached to a graded item, or the gradebook scopes were not enabled "
                    + "on the registration.");
        }

        LineItem lineItem = ags.findOrCreateLineItem(launch,
                "LTI demo — " + (launch.resourceLinkTitle() == null
                        ? "activity" : launch.resourceLinkTitle()),
                100.0,
                launch.taskId() == null ? "ltidemo-activity" : launch.taskId());

        Score toPost = awaitingReview
                ? Score.awaitingReview(launch.subject(), score, 100.0,
                        "Posted by the LTI demo tool; left for the instructor to confirm.")
                : Score.graded(launch.subject(), score, 100.0, "Posted by the LTI demo tool.");

        ags.postScore(launch, lineItem, toPost);

        model.addAttribute("launch", launch);
        model.addAttribute("lineItem", lineItem);
        model.addAttribute("score", toPost);
        model.addAttribute("grantedScopes", ags.grantedScopes(launch));
        return "service-grade";
    }

    @PostMapping("/lti/services/roster")
    public String roster(@RequestParam(defaultValue = "false") boolean thisLinkOnly,
                         HttpSession session,
                         Model model) {

        LtiLaunch launch = launchFrom(session);
        if (!launch.isInstructor()) {
            throw new ServiceCallException(
                    "Only an instructor can retrieve a course roster.");
        }
        if (!nrps.isAvailable(launch)) {
            throw new ServiceCallException(
                    "This launch carried no NRPS claim, so the platform has not granted roster "
                    + "access for this course.");
        }

        List<Member> members = thisLinkOnly
                ? nrps.resourceLinkRoster(launch)
                : nrps.courseRoster(launch);

        model.addAttribute("launch", launch);
        model.addAttribute("members", members);
        model.addAttribute("learners", members.stream().filter(Member::isLearner).count());
        model.addAttribute("instructors", members.stream().filter(Member::isInstructor).count());
        model.addAttribute("inactive", members.stream().filter(m -> !m.isActive()).count());
        model.addAttribute("withoutEmail",
                members.stream().filter(m -> m.email() == null || m.email().isBlank()).count());
        return "service-roster";
    }

    private LtiLaunch launchFrom(HttpSession session) {
        LtiLaunch launch = (LtiLaunch) session.getAttribute(LaunchController.SESSION_LAUNCH);
        if (launch == null) {
            throw new ServiceCallException(
                    "No launch in session. These endpoints run in the context of a launch and "
                    + "cannot be called directly.");
        }
        return launch;
    }
}
