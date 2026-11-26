package com.example.scaffold;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@Profile("cognito")
class CognitoPages {
    @GetMapping(CognitoSettings.SIGNED_OUT)
    String signedOut() {
        return "signed-out";
    }

    @GetMapping(CognitoSettings.LOGIN_FAILED)
    String loginFailed() {
        return "login-failed";
    }
}
