package org.khorum.oss.relikquary.integration

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.khorum.oss.relikquary.storage.ArtifactStorage
import org.khorum.oss.relikquary.storage.S3ArtifactStorage
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * Backend selection (FR-002): `relikquary.storage.backend=s3` activates the S3 backend. (The default
 * filesystem backend is exercised by every other `@SpringBootTest` in the suite.) The S3 client is
 * built lazily, so the context starts without contacting S3.
 */
@SpringBootTest(
    properties = [
        "relikquary.security.enabled=false",
        "relikquary.storage.backend=s3",
        "relikquary.storage.s3.endpoint=http://127.0.0.1:1",
        "relikquary.storage.s3.bucket=relikquary",
        "relikquary.storage.s3.access-key=test",
        "relikquary.storage.s3.secret-key=test",
    ],
)
class StorageBackendSelectionTest {

    @Autowired
    lateinit var storage: ArtifactStorage

    @Test
    fun `s3 backend is selected when configured`() {
        assertTrue(storage is S3ArtifactStorage) { "expected S3ArtifactStorage, got ${storage::class.simpleName}" }
    }
}
