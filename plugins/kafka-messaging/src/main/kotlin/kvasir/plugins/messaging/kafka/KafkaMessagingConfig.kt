package kvasir.plugins.messaging.kafka

import io.smallrye.config.ConfigMapping

@ConfigMapping(prefix = "kvasir.messaging.kafka")
interface KafkaMessagingConfig {

    fun advertisedHostname(): String

    fun bootstrapServers(): String

}