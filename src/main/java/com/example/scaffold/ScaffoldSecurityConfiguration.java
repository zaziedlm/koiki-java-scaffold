package com.example.scaffold;

import static org.springframework.security.config.Customizer.withDefaults;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Application-owned browser security chain.
 *
 * <p>The chain lists its routes explicitly and runs before the KOIKI fallback, which denies every
 * request that no application chain matches. CSRF and the standard security headers stay enabled.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ServerProperties.class)
class ScaffoldSecurityConfiguration {

    /**
     * Session cookie for the JDBC Session, configured through {@code server.servlet.session.cookie.*}.
     * KOIKI's Session logout support requires the application to provide this bean.
     */
    @Bean
    CookieSerializer scaffoldCookieSerializer(ServerProperties serverProperties) {
        var cookie = serverProperties.getServlet().getSession().getCookie();
        var serializer = new DefaultCookieSerializer();
        var mapper = PropertyMapper.get();
        mapper.from(cookie::getName).to(serializer::setCookieName);
        mapper.from(cookie::getDomain).to(serializer::setDomainName);
        mapper.from(cookie::getPath).to(serializer::setCookiePath);
        mapper.from(cookie::getHttpOnly).to(serializer::setUseHttpOnlyCookie);
        mapper.from(cookie::getSecure).to(serializer::setUseSecureCookie);
        mapper.from(cookie::getMaxAge).asInt(Duration::getSeconds).to(serializer::setCookieMaxAge);
        mapper.from(cookie::getSameSite).to(sameSite -> serializer.setSameSite(sameSite.attributeValue()));
        mapper.from(cookie::getPartitioned).to(serializer::setPartitioned);
        return serializer;
    }

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
