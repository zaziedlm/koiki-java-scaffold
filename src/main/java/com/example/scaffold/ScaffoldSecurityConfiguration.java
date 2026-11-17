package com.example.scaffold;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Application-owned browser security chain.
 *
 * <p>The chain lists its routes explicitly and runs before the KOIKI fallback, which denies every
 * request that no application chain matches. CSRF and the standard security headers stay enabled.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ScaffoldSecurityConfiguration {

    @Bean
    @Order(0)
    SecurityFilterChain scaffoldBrowserSecurityFilterChain(
            HttpSecurity http, Customizer<HttpSecurity> koikiSessionLogoutCustomizer) throws Exception {
        http.securityMatcher("/", "/login", "/logout", "/koiki-web/**", "/webjars/**");
        http.authorizeHttpRequests(requests -> requests
                .requestMatchers("/login", "/koiki-web/**", "/webjars/**").permitAll()
                .anyRequest().authenticated());
        http.formLogin(form -> form.defaultSuccessUrl("/", true));
        http.csrf(withDefaults());
        http.headers(withDefaults());
        koikiSessionLogoutCustomizer.customize(http);
        return http.build();
    }
}
