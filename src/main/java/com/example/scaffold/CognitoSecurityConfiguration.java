package com.example.scaffold;

import static org.springframework.security.config.Customizer.withDefaults;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.koikifw.audit.AuditActor;
import org.koikifw.audit.AuditEvent;
import org.koikifw.audit.AuditRecordingException;
import org.koikifw.audit.AuditResult;
import org.koikifw.audit.SecurityAuditRecorder;
import org.koikifw.identity.IdentityQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.web.util.UriComponentsBuilder;

@Configuration(proxyBeanMethods = false)
@Profile("cognito")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class CognitoSecurityConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(CognitoSecurityConfiguration.class);

    @Bean
    @Order(0)
    SecurityFilterChain cognitoBrowserSecurityFilterChain(HttpSecurity http, Environment environment,
            JdbcClient jdbc, IdentityQuery identities, SecurityAuditRecorder audit,
            Customizer<HttpSecurity> koikiSessionLogoutCustomizer)
            throws Exception {
        boolean aws = environment.acceptsProfiles(Profiles.of("aws"));
        if (environment.getProperty("koiki.identity.local-authentication.enabled", Boolean.class, false)) {
            throw new IllegalStateException("Disable KOIKI local authentication in Cognito mode.");
        }
        String origin = CognitoSettings.origin(environment, "SCAFFOLD_COGNITO_PUBLIC_ORIGIN", !aws);
        boolean secureCookie = environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, true);
        if ("https".equals(URI.create(origin).getScheme()) && !secureCookie) {
            throw new IllegalStateException("Cognito HTTPS deployments require Secure session cookies.");
        }
        var registration = registration(environment, origin);
        var identityLinks = new CognitoIdentityLinks(jdbc, identities);
        String logoutUrl = UriComponentsBuilder.fromUriString(
                        CognitoSettings.origin(environment, "SCAFFOLD_COGNITO_DOMAIN", false) + "/logout")
                .queryParam("client_id", "{client}")
                .queryParam("logout_uri", "{destination}").encode()
                .buildAndExpand(registration.getClientId(), origin + CognitoSettings.SIGNED_OUT).toUriString();

        http.securityMatcher("/", CognitoSettings.START, CognitoSettings.CALLBACK,
                CognitoSettings.LOGIN_FAILED, CognitoSettings.SIGNED_OUT, "/logout", "/koiki-web/**", "/webjars/**");
        http.authorizeHttpRequests(requests -> requests
                .requestMatchers(CognitoSettings.START, CognitoSettings.CALLBACK, CognitoSettings.LOGIN_FAILED,
                        CognitoSettings.SIGNED_OUT, "/koiki-web/**", "/webjars/**").permitAll()
                .anyRequest().access((authentication, context) -> new AuthorizationDecision(
                        authentication.get().isAuthenticated()
                                && authentication.get().getPrincipal() instanceof CognitoIdentityLinks.Principal)));
        http.exceptionHandling(errors -> errors.authenticationEntryPoint(
                new LoginUrlAuthenticationEntryPoint(origin + CognitoSettings.START)));
        http.oauth2Login(login -> {
            login.clientRegistrationRepository(new InMemoryClientRegistrationRepository(registration))
                    .authorizedClientRepository(new LoginOnlyClientRepository())
                    .loginPage(CognitoSettings.START)
                    .successHandler((request, response, authentication) -> {
                        record(audit, "LOGIN", AuditResult.SUCCESS, authentication);
                        response.sendRedirect(origin + "/");
                    })
                    .failureHandler((request, response, failure) -> {
                        record(audit, "LOGIN", AuditResult.FAILURE, null);
                        response.sendRedirect(origin + CognitoSettings.LOGIN_FAILED);
                    });
            login.withObjectPostProcessor(new ObjectPostProcessor<OAuth2LoginAuthenticationFilter>() {
                @Override
                public <T extends OAuth2LoginAuthenticationFilter> T postProcess(T filter) {
                    filter.setAuthenticationResultConverter(identityLinks::authenticate);
                    return filter;
                }
            });
        });
        http.csrf(withDefaults());
        http.headers(withDefaults());
        koikiSessionLogoutCustomizer.customize(http);
        http.logout(logout -> logout.logoutSuccessHandler(
                (request, response, authentication) -> {
                    record(audit, "LOGOUT", AuditResult.SUCCESS, authentication);
                    response.sendRedirect(logoutUrl);
                }));
        return http.build();
    }

    private static void record(SecurityAuditRecorder audit, String action, AuditResult result,
            @Nullable Authentication authentication) {
        AuditActor actor = authentication != null
                && authentication.getPrincipal() instanceof CognitoIdentityLinks.Principal principal
                ? AuditActor.user(principal.userId().toString()) : AuditActor.anonymous();
        try {
            audit.record(AuditEvent.of("SCAFFOLD_COGNITO", actor, action, result));
        } catch (AuditRecordingException exception) {
            LOGGER.warn("Unable to record Cognito {} audit event", action);
        }
    }

    private static ClientRegistration registration(Environment environment, String origin) {
        String issuer = CognitoSettings.issuer(environment);
        String domain = CognitoSettings.origin(environment, "SCAFFOLD_COGNITO_DOMAIN", false);
        return ClientRegistration.withRegistrationId("cognito")
                .clientId(CognitoSettings.required(environment, "SCAFFOLD_COGNITO_CLIENT_ID"))
                .clientSecret(CognitoSettings.required(environment, "SCAFFOLD_COGNITO_CLIENT_SECRET"))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(origin + CognitoSettings.CALLBACK)
                .scope("openid")
                .issuerUri(issuer)
                .jwkSetUri(issuer + "/.well-known/jwks.json")
                .authorizationUri(domain + "/oauth2/authorize")
                .tokenUri(domain + "/oauth2/token")
                .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build())
                .build();
    }

    /** This application does not call downstream APIs using Cognito access tokens. */
    private static final class LoginOnlyClientRepository implements OAuth2AuthorizedClientRepository {
        @Override
        @SuppressWarnings("TypeParameterUnusedInFormals") // Signature inherited from Spring Security.
        public <T extends OAuth2AuthorizedClient> @Nullable T loadAuthorizedClient(
                String registrationId, Authentication principal, HttpServletRequest request) { return null; }

        @Override
        public void saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {}

        @Override
        public void removeAuthorizedClient(String registrationId, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {}
    }
}
