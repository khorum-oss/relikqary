package org.khorum.oss.relikquary.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain

/**
 * Spring Security configuration for the repository protocol (feature 002, contracts/auth.md):
 * `PUT` (publish) requires the `PUBLISH` role; `GET`/`HEAD` (resolve) stay open. When
 * [SecurityProperties.enabled] is false, all requests are permitted (local-dev opt-out).
 */
@Configuration
class SecurityConfig(private val properties: SecurityProperties) {

    @Bean
    fun passwordEncoder(): PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    @Bean
    @Suppress("SpreadOperator")
    fun userDetailsService(): UserDetailsService {
        val users = properties.users.map { user ->
            User.withUsername(user.username)
                .password(user.password)
                .roles(*user.roles.toTypedArray())
                .build()
        }
        return InMemoryUserDetailsManager(users)
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http.csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        if (properties.enabled) {
            http.authorizeHttpRequests { auth ->
                auth.requestMatchers(HttpMethod.PUT, "/**").hasRole(PUBLISH_ROLE)
                    .anyRequest().permitAll()
            }.httpBasic { it.realmName("relikquary") }
        } else {
            http.authorizeHttpRequests { it.anyRequest().permitAll() }
        }
        return http.build()
    }

    private companion object {
        const val PUBLISH_ROLE = "PUBLISH"
    }
}
