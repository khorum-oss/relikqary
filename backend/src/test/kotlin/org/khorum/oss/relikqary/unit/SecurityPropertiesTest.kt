package org.khorum.oss.relikqary.unit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.khorum.oss.relikqary.config.SecurityProperties

class SecurityPropertiesTest {

    @Test
    fun `auth is enabled by default with no users`() {
        val props = SecurityProperties()
        assertTrue(props.enabled)
        assertTrue(props.users.isEmpty())
    }

    @Test
    fun `a user defaults to the publish role`() {
        val user = SecurityProperties.User(username = "ci", password = "{noop}x")
        assertEquals(listOf("PUBLISH"), user.roles)
    }
}
