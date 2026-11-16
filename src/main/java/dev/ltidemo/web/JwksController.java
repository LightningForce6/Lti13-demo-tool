package dev.ltidemo.web;

import dev.ltidemo.config.LtiProperties;
import dev.ltidemo.config.PlatformRegistration;
import dev.ltidemo.config.PlatformRegistry;
import dev.ltidemo.key.ToolKeyService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Controller
public class JwksController {

    private final ToolKeyService keys;
    private final LtiProperties properties;
    private final PlatformRegistry registry;

    public JwksController(ToolKeyService keys, LtiProperties properties, PlatformRegistry registry) {
        this.keys = keys;
        this.properties = properties;
        this.registry = registry;
    }

    /**
     * This tool's public keys, for platforms verifying our Deep Linking responses.
     *
     * <p>Served with a modest max-age. Too long and a rotation takes a day to propagate; too
     * short and every platform refetches constantly. An hour is the usual compromise.
     */
    @GetMapping(value = "/.well-known/jwks.json", produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(keys.publicJwks());
    }

    /** The values an LMS administrator needs when registering this tool. */
    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("baseUrl", properties.getToolBaseUrl());
        model.addAttribute("loginUrl", properties.getToolBaseUrl() + "/lti/login");
        model.addAttribute("launchUrl", properties.launchUrl());
        model.addAttribute("jwksUrl", properties.jwksUrl());
        List<PlatformRegistration> platforms = registry.all();
        model.addAttribute("platforms", platforms);
        return "home";
    }
}
