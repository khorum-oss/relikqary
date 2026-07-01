package org.khorum.oss.relikquary.config

import jakarta.servlet.http.HttpServletResponse
import org.khorum.oss.relikquary.security.ApiTokenAuthenticationProvider
import org.khorum.oss.relikquary.security.ManagedUserService
import org.khorum.oss.relikquary.security.RepositoryAuthorizationManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint

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

    /**
     * Config users first, managed (DB) users second (feature 016, US8). A username defined in config
     * always wins, so introducing managed accounts never locks a config user out.
     */
    @Bean
    @Suppress("SpreadOperator")
    fun userDetailsService(managedUsers: ManagedUserService): UserDetailsService {
        val configUsers = InMemoryUserDetailsManager(
            properties.users.map { user ->
                User.withUsername(user.username)
                    .password(user.password)
                    .roles(*user.roles.toTypedArray())
                    .build()
            },
        )
        return UserDetailsService { username ->
            runCatching { configUsers.loadUserByUsername(username) }.getOrNull()
                ?: managedUsers.loadUserDetails(username)
                ?: throw UsernameNotFoundException(username)
        }
    }

    /**
     * Authenticate API tokens (presented as the Basic password) before configured users: the token
     * provider handles `rlq_…` credentials and passes everything else through to the user provider.
     */
    @Bean
    fun authenticationManager(
        userDetailsService: UserDetailsService,
        passwordEncoder: PasswordEncoder,
        tokenProvider: ApiTokenAuthenticationProvider,
    ): AuthenticationManager {
        val dao = DaoAuthenticationProvider(userDetailsService)
        dao.setPasswordEncoder(passwordEncoder)
        return ProviderManager(tokenProvider, dao)
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity, authenticationManager: AuthenticationManager): SecurityFilterChain {
        http.csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authenticationManager(authenticationManager)
        if (properties.enabled) {
            http.authorizeHttpRequests { auth ->
                // Orchestrator probes stay open; every other operational endpoint (detailed health,
                // metrics, info, …) requires the global PUBLISH role (feature 010). The Maven/`/api`
                // surface continues through the repository authorization manager unchanged.
                auth.requestMatchers("/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                    .requestMatchers("/actuator/**").hasRole("PUBLISH")
                    .anyRequest().access(authorizationManager)
            }.httpBasic { it.authenticationEntryPoint(entryPoint()) }
        } else {
            http.authorizeHttpRequests { it.anyRequest().permitAll() }
        }
        return http.build()
    }

    /**
     * Returns `401` with a `WWW-Authenticate: Basic` challenge for Maven/Gradle clients, but omits the
     * challenge header for browser XHR (requests sending `X-Requested-With: XMLHttpRequest`) so the
     * browser does not hijack the response with its native Basic-auth dialog — the web UI shows its own
     * login prompt instead. The Maven/Gradle wire behaviour (002) is unchanged.
     */
    private fun entryPoint(): AuthenticationEntryPoint {
        val basic = BasicAuthenticationEntryPoint().apply { setRealmName("relikquary") }
        return AuthenticationEntryPoint { request, response, ex ->
            if (request.getHeader("X-Requested-With") == "XMLHttpRequest") {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED)
            } else {
                basic.commence(request, response, ex)
            }
        }
    }
}
