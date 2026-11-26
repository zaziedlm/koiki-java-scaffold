package com.example.scaffold;

import java.net.URI;
import org.springframework.core.env.Environment;

final class CognitoSettings {
    static final String START = "/oauth2/authorization/cognito";
    static final String CALLBACK = "/login/oauth2/code/cognito";
    static final String SIGNED_OUT = "/signed-out";
    static final String LOGIN_FAILED = "/login-failed";

    private CognitoSettings() {}

    static String issuer(Environment environment) {
        URI issuer = url(environment, "SCAFFOLD_COGNITO_ISSUER", false);
        if (issuer.getPath().length() < 2 || issuer.getPath().endsWith("/")) {
            throw new IllegalArgumentException("SCAFFOLD_COGNITO_ISSUER must include the User Pool path.");
        }
        return issuer.toString();
    }

    static String origin(Environment environment, String name, boolean allowLocalHttp) {
        URI uri = url(environment, name, allowLocalHttp);
        if (!uri.getPath().isEmpty()) {
            throw new IllegalArgumentException(name + " must be an origin without a path or trailing slash.");
        }
        return uri.toString();
    }

    static String required(Environment environment, String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Set " + name + ".");
        }
        return value;
    }

    private static URI url(Environment environment, String name, boolean allowLocalHttp) {
        URI uri;
        try {
            uri = URI.create(required(environment, name));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Set a valid URL for " + name + ".");
        }
        boolean schemeAllowed = "https".equals(uri.getScheme())
                || (allowLocalHttp && "http".equals(uri.getScheme()) && "localhost".equals(uri.getHost()));
        if (!schemeAllowed || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException(name + " must use HTTPS (HTTP localhost only for local development).");
        }
        return uri;
    }
}
