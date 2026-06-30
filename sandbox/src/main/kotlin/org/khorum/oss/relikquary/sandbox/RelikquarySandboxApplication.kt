package org.khorum.oss.relikquary.sandbox

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class RelikquarySandboxApplication

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<RelikquarySandboxApplication>(*args)
}
