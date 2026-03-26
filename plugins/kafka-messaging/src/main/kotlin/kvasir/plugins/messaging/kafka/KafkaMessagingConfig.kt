package kvasir.plugins.messaging.kafka

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault

@ConfigMapping(prefix = "kvasir.messaging.kafka")
interface KafkaMessagingConfig {

    fun advertisedHostname(): String

    fun bootstrapServers(): String

    fun autoCreateTopics(): List<AutoCreateTopicConfig>

}

interface AutoCreateTopicConfig {

    fun topicName(): String

    @WithDefault("1")
    fun partitions(): Int

    @WithDefault("1")
    fun replicationFactor(): Short

}