package com.example.scaffold;

import org.koikifw.audit.AuditActor;
import org.koikifw.audit.AuditEvent;
import org.koikifw.audit.AuditResult;
import org.koikifw.audit.BusinessAuditRecorder;
import org.koikifw.identity.FrameworkUserId;
import org.koikifw.identity.IdentityQuery;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** An operator command for the application-owned link table. */
@Configuration(proxyBeanMethods = false)
@Profile("cognito-link")
@ConditionalOnNotWebApplication
class CognitoLinkConfiguration {
    @Bean
    ApplicationRunner linkCognitoIdentity(Environment environment, JdbcClient jdbc, IdentityQuery identities,
            BusinessAuditRecorder audit, PlatformTransactionManager manager) {
        return args -> {
            String issuer = CognitoSettings.issuer(environment);
            String subject = CognitoSettings.required(environment, "SCAFFOLD_COGNITO_LINK_SUBJECT");
            if (subject.length() > 255 || subject.codePoints().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("SCAFFOLD_COGNITO_LINK_SUBJECT must be the Cognito sub value.");
            }
            var id = FrameworkUserId.parse(CognitoSettings.required(environment, "SCAFFOLD_COGNITO_LINK_USER_ID"));
            new TransactionTemplate(manager).executeWithoutResult(transaction -> {
                new CognitoIdentityLinks(jdbc, identities).link(issuer, subject, id);
                audit.record(AuditEvent.of("SCAFFOLD_COGNITO", AuditActor.anonymous(),
                        "LINK_COGNITO_IDENTITY", AuditResult.SUCCESS).withSubject(id.toString()));
            });
            System.out.println("Cognito identity associated with an active KOIKI user.");
        };
    }
}
