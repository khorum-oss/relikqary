package org.khorum.oss.relikquary.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.net.URI

/**
 * Builds the [S3Client] for the S3 storage backend, created only when `relikquary.storage.backend=s3`.
 * Supports a custom endpoint and path-style addressing so DigitalOcean Spaces, MinIO, and other
 * S3-compatible services work, not just AWS S3.
 */
@Configuration
@ConditionalOnProperty(name = ["relikquary.storage.backend"], havingValue = "s3")
class S3ClientConfig(private val properties: StorageProperties) {

    @Bean(destroyMethod = "close")
    fun s3Client(): S3Client {
        val s3 = properties.s3
        val builder = S3Client.builder()
            .region(Region.of(s3.region))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(s3.accessKey, s3.secretKey)),
            )
            .serviceConfiguration(
                S3Configuration.builder().pathStyleAccessEnabled(s3.pathStyleAccess).build(),
            )
        if (s3.endpoint.isNotBlank()) {
            builder.endpointOverride(URI.create(s3.endpoint))
        }
        return builder.build()
    }
}
