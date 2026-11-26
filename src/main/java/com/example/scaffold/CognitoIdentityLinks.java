package com.example.scaffold;

import java.io.Serializable;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.koikifw.identity.AuthenticationSource;
import org.koikifw.identity.FrameworkPrincipal;
import org.koikifw.identity.FrameworkUserId;
import org.koikifw.identity.IdentityQuery;
import org.koikifw.identity.IdentityUser;
import org.koikifw.identity.UserStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

final class CognitoIdentityLinks {

    private final JdbcClient jdbc;
    private final IdentityQuery identities;

    CognitoIdentityLinks(JdbcClient jdbc, IdentityQuery identities) {
        this.jdbc = jdbc;
        this.identities = identities;
    }

    OAuth2AuthenticationToken authenticate(OAuth2LoginAuthenticationToken login) {
        if (!(login.getPrincipal() instanceof OidcUser user) || user.getSubject() == null
                || user.getSubject().isBlank() || user.getSubject().length() > 255) {
            throw denied();
        }
        // Spring Security has already verified the token against this registration's issuer.
        String issuer = login.getClientRegistration().getProviderDetails().getIssuerUri();
        if (issuer == null) {
            throw denied();
        }
        var id = linkedUser(issuer, user.getSubject()).orElseThrow(CognitoIdentityLinks::denied);
        var identity = identities.findById(id).filter(candidate -> candidate.status() == UserStatus.ACTIVE)
                .orElseThrow(CognitoIdentityLinks::denied);
        // Only the application's identity is stored in the JDBC security context.
        var principal = new Principal(id, identity.permissionCodes());
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(),
                login.getClientRegistration().getRegistrationId());
    }

    void link(String issuer, String subject, FrameworkUserId id) {
        if (identities.findById(id).map(IdentityUser::status).orElse(null) != UserStatus.ACTIVE) {
            throw new IllegalArgumentException("Linking requires an existing active KOIKI user.");
        }
        jdbc.sql("""
                insert into scaffold_cognito_identity_link (issuer, subject, framework_user_id)
                values (:issuer, :subject, :id) on conflict do nothing
                """).param("issuer", issuer).param("subject", subject).param("id", id.value()).update();
        if (linkedUser(issuer, subject).filter(id::equals).isEmpty()) {
            throw new IllegalStateException("A different link already exists for this subject or user.");
        }
    }

    private java.util.Optional<FrameworkUserId> linkedUser(String issuer, String subject) {
        return jdbc.sql("""
                select framework_user_id from scaffold_cognito_identity_link
                where issuer = :issuer and subject = :subject
                """).param("issuer", issuer).param("subject", subject)
                .query(UUID.class).optional().map(id -> FrameworkUserId.parse(id.toString()));
    }

    private static OAuth2AuthenticationException denied() {
        return new OAuth2AuthenticationException("scaffold_access_denied");
    }

    record Principal(FrameworkUserId userId, Set<String> permissions)
            implements FrameworkPrincipal, OAuth2User, Serializable {

        Principal {
            permissions = Set.copyOf(permissions);
        }

        @Override public AuthenticationSource authenticationSource() { return AuthenticationSource.OIDC; }
        @Override public String getName() { return userId.toString(); }
        @Override public Map<String, Object> getAttributes() { return Map.of(); }

        @Override
        public Set<SimpleGrantedAuthority> getAuthorities() {
            return permissions.stream().map(SimpleGrantedAuthority::new)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }
}
