package org.khorum.oss.relikquary.protocol.admin

import org.khorum.oss.relikquary.persistence.ManagedUser
import org.khorum.oss.relikquary.protocol.dto.CreateUserRequest
import org.khorum.oss.relikquary.protocol.dto.UserResponse
import org.khorum.oss.relikquary.security.ManagedUserService
import org.khorum.oss.relikquary.security.UsernameConflictException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Admin API for managed users (feature 016, Phase 3, US8): list, create, delete. Part of the
 * `/api/admin` surface, so it requires the global PUBLISH authority. Managed users coexist with the
 * static-config users and are the source for the Users screen; the password is never returned.
 */
@RestController
@RequestMapping("/api/admin/users")
class UserController(private val users: ManagedUserService) {

    @GetMapping
    fun list(): List<UserResponse> = users.list().map(::toResponse)

    @PostMapping
    fun create(@RequestBody request: CreateUserRequest): ResponseEntity<UserResponse> {
        val created = users.create(
            username = request.username.orEmpty(),
            email = request.email,
            password = request.password.orEmpty(),
            roles = request.roles ?: emptyList(),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created))
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): ResponseEntity<Void> =
        if (users.delete(id)) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()

    private fun toResponse(user: ManagedUser) = UserResponse(
        id = user.id,
        username = user.username,
        email = user.email,
        roles = user.roleList(),
        lastActiveAt = user.lastActiveAt,
        createdAt = user.createdAt,
    )

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(e: IllegalArgumentException): ResponseEntity<String> =
        ResponseEntity.badRequest().body(e.message)

    @ExceptionHandler(UsernameConflictException::class)
    fun handleConflict(e: UsernameConflictException): ResponseEntity<String> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(e.message)
}
