package org.khorum.oss.relikquary.config

import org.khorum.oss.relikquary.security.RepositoryAuthorizationManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain

/**
 * Spring Security configuration for the repository protocol (features 002 + 007). When auth is enabled,
 * every request is authorized by [RepositoryAuthorizationManager], which applies per-repository
 * READ/PUBLISH/DELETE policy (defaulting to open reads and global-`PUBLISH`-gated writes). When
 * [SecurityProperties.enabled] is false, all requests are permitted (local-dev opt-out).
 */
@Configuration
class SecurityConfig(
    private val properties: SecurityProperties,
    private val authorizationManager: RepositoryAuthorizationManager,
) {

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
                auth.anyRequest().access(authorizationManager)
            }.httpBasic { it.realmName("relikquary") }
        } else {
            http.authorizeHttpRequests { it.anyRequest().permitAll() }
        }
        return http.build()
    }
}
