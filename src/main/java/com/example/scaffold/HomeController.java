package com.example.scaffold;

import org.jspecify.annotations.Nullable;
import org.koikifw.identity.FrameworkPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Authenticated landing page. Replace with the project's own entry point. */
@Controller
class HomeController {

    @GetMapping("/")
    String home(Model model, @Nullable Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof FrameworkPrincipal principal) {
            model.addAttribute("userId", principal.userId().toString());
            model.addAttribute("permissions", principal.permissions());
        }
        return "home";
    }
}
