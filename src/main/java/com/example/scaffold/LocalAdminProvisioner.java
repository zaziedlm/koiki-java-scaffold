package com.example.scaffold;

import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;
import org.koikifw.audit.AuditActor;
import org.koikifw.audit.AuditEvent;
import org.koikifw.audit.AuditResult;
import org.koikifw.audit.BusinessAuditRecorder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Local, first-user provisioning pinned to the preview.2 Identity schema; never a Web API. */
final class LocalAdminProvisioner {

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final TransactionTemplate transaction;
    private final BusinessAuditRecorder audit;

    LocalAdminProvisioner(JdbcClient jdbc, PasswordEncoder encoder,
            PlatformTransactionManager manager, BusinessAuditRecorder audit) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.transaction = new TransactionTemplate(manager);
        this.audit = audit;
    }

    void create(String email, char[] password) {
        String address = email.trim();
        if (address.isEmpty() || address.length() > 320 || !address.contains("@")
                || address.codePoints().anyMatch(c -> c > 127 || Character.isWhitespace(c)
                        || Character.isISOControl(c))) {
            throw new IllegalArgumentException("Enter a valid ASCII email address.");
        }
        String normalized = Normalizer.normalize(new String(password), Normalizer.Form.NFC);
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 15 || length > 128) {
            throw new IllegalArgumentException("Use a password of 15 to 128 characters.");
        }
        // The Framework default is bcrypt: reject inputs it would truncate or refuse.
        if (normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("The password must fit within 72 UTF-8 bytes.");
        }
        String encoded = encoder.encode(normalized);
        transaction.executeWithoutResult(status -> {
            // Serializes concurrent first-user attempts. Existing accounts are never overwritten.
            jdbc.sql("lock table koiki_user in exclusive mode").update();
            if (jdbc.sql("select count(*) from koiki_user").query(Long.class).single() != 0) {
                throw new IllegalStateException("Identity already has users; initial registration refused.");
            }
            UUID user = UUID.randomUUID();
            UUID role = UUID.randomUUID();
            UUID permission = UUID.randomUUID();
            jdbc.sql("""
                    insert into koiki_user(user_id, email, canonical_email, status, version)
                    values (:id, :email, :canonical, 'ACTIVE', 0)
                    """).param("id", user).param("email", address)
                    .param("canonical", address.toLowerCase(Locale.ROOT)).update();
            jdbc.sql("insert into koiki_password_credential(user_id, encoded_password) values (:id, :password)")
                    .param("id", user).param("password", encoded).update();
            jdbc.sql("insert into koiki_role(role_id, role_code) values (:id, 'SCAFFOLD_ADMIN')")
                    .param("id", role).update();
            jdbc.sql("insert into koiki_permission(permission_id, permission_code) values (:id, 'SCAFFOLD:ADMIN')")
                    .param("id", permission).update();
            jdbc.sql("insert into koiki_user_role(user_id, role_id) values (:user, :role)")
                    .param("user", user).param("role", role).update();
            jdbc.sql("insert into koiki_role_permission(role_id, permission_id) values (:role, :permission)")
                    .param("role", role).param("permission", permission).update();
            // No authenticated actor exists yet. Do not fabricate a FrameworkPrincipal.
            audit.record(AuditEvent.of("SCAFFOLD_BOOTSTRAP", AuditActor.anonymous(),
                    "CREATE_INITIAL_ADMIN", AuditResult.SUCCESS).withSubject(user.toString()));
        });
    }
}
