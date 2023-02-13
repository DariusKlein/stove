package com.trendyol.stove.testing.e2e.kafka

import arrow.core.Option
import arrow.core.getOrElse
import com.trendyol.stove.testing.e2e.containers.DEFAULT_REGISTRY
import com.trendyol.stove.testing.e2e.containers.withProvidedRegistry
import com.trendyol.stove.testing.e2e.kafka.intercepting.InterceptionOptions
import com.trendyol.stove.testing.e2e.kafka.intercepting.TestSystemKafkaInterceptor
import com.trendyol.stove.testing.e2e.messaging.MessagingSystem
import com.trendyol.stove.testing.e2e.serialization.StoveJacksonJsonSerializer
import com.trendyol.stove.testing.e2e.serialization.StoveJsonSerializer
import com.trendyol.stove.testing.e2e.serialization.deserialize
import com.trendyol.stove.testing.e2e.system.TestSystem
import com.trendyol.stove.testing.e2e.system.abstractions.*
import io.github.nomisRev.kafka.Admin
import io.github.nomisRev.kafka.AdminSettings
import io.github.nomisRev.kafka.receiver.KafkaReceiver
import io.github.nomisRev.kafka.receiver.ReceiverSettings
import io.github.nomisRev.kafka.sendAwait
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.*
import org.testcontainers.containers.KafkaContainer
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

data class KafkaExposedConfiguration(
    val boostrapServers: String,
) : ExposedConfiguration

data class KafkaSystemOptions(
    val registry: String = DEFAULT_REGISTRY,
    val ports: List<Int> = listOf(9092, 9093),
    val errorTopicSuffixes: List<String> = listOf("error", "errorTopic", "retry", "retryTopic"),
    val jsonSerializer: StoveJsonSerializer = StoveJacksonJsonSerializer(),
    override val configureExposedConfiguration: (KafkaExposedConfiguration) -> List<String> = { _ -> listOf() },
) : SystemOptions, ConfiguresExposedConfiguration<KafkaExposedConfiguration>

data class KafkaContext(
    val container: KafkaContainer,
    val options: KafkaSystemOptions,
)

fun TestSystem.kafka(): KafkaSystem =
    getOrNone<KafkaSystem>().getOrElse { throw SystemNotRegisteredException(KafkaSystem::class) }

fun TestSystem.withKafka(
    options: KafkaSystemOptions = KafkaSystemOptions(),
): TestSystem {
    val kafka = withProvidedRegistry("confluentinc/cp-kafka:latest", options.registry) {
        KafkaContainer(it).withExposedPorts(*options.ports.toTypedArray()).withEmbeddedZookeeper()
    }
    getOrRegister(KafkaSystem(this, KafkaContext(kafka, options)))
    return this
}

class KafkaSystem(
    override val testSystem: TestSystem,
    private val context: KafkaContext,
) : MessagingSystem, ExposesConfiguration, RunAware, AfterRunAware {

    private lateinit var exposedConfiguration: KafkaExposedConfiguration
    private lateinit var adminClient: Admin
    private lateinit var kafkaProducer: KafkaProducer<String, Any>
    private lateinit var subscribeToAllConsumer: SubscribeToAll
    private lateinit var interceptor: TestSystemKafkaInterceptor

    override suspend fun publish(
        topic: String,
        message: Any,
        key: Option<String>,
        headers: Map<String, String>,
        testCase: Option<String>,
    ): KafkaSystem {
        val record = ProducerRecord<String, Any>(topic, message)
        testCase.map { record.headers().add("testCase", it.toByteArray()) }
        kafkaProducer.sendAwait(record)
        return this
    }

    override suspend fun shouldBeConsumed(
        atLeastIn: Duration,
        message: Any,
    ): KafkaSystem = interceptor
        .waitUntilConsumed(atLeastIn, message::class) { actual -> actual.exists { it == message } }
        .let { this }

    override suspend fun <T : Any> shouldBeConsumedOnCondition(
        atLeastIn: Duration,
        condition: (T) -> Boolean,
        clazz: KClass<T>,
    ): KafkaSystem = interceptor
        .waitUntilConsumed(atLeastIn, clazz) { actual -> actual.exists { condition(it) } }
        .let { this }

    override suspend fun run() {
        context.container.start()
        exposedConfiguration = KafkaExposedConfiguration(context.container.bootstrapServers)
        adminClient = createAdminClient(exposedConfiguration)
        kafkaProducer = createProducer(exposedConfiguration)
    }

    override suspend fun afterRun() {
        interceptor = TestSystemKafkaInterceptor(
            adminClient,
            StoveJacksonJsonSerializer(),
            InterceptionOptions(errorTopicSuffixes = context.options.errorTopicSuffixes)
        )
        subscribeToAllConsumer = SubscribeToAll(
            adminClient,
            consumer(exposedConfiguration),
            interceptor
        )
        subscribeToAllConsumer.start()
    }

    private fun consumer(
        cfg: KafkaExposedConfiguration,
    ): KafkaReceiver<String, String> = mapOf(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to cfg.boostrapServers,
        ConsumerConfig.GROUP_ID_CONFIG to "stove-kafka-subscribe-to-all",
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StoveKafkaValueDeserializer::class.java,
        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
        ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG to 2.milliseconds.inWholeMilliseconds.toInt()
    ).let {
        KafkaReceiver(
            ReceiverSettings(
                cfg.boostrapServers,
                StringDeserializer(),
                StoveKafkaValueDeserializer(),
                SubscribeToAllGroupId,
                properties = it.toProperties()
            )
        )
    }

    private fun createProducer(exposedConfiguration: KafkaExposedConfiguration): KafkaProducer<String, Any> = mapOf(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to exposedConfiguration.boostrapServers,
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StoveKafkaValueSerializer::class.java
    ).let { KafkaProducer(it) }

    private fun createAdminClient(exposedConfiguration: KafkaExposedConfiguration): Admin = mapOf<String, Any>(
        AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to exposedConfiguration.boostrapServers,
        AdminClientConfig.CLIENT_ID_CONFIG to "stove-kafka-admin-client"
    ).let { Admin(AdminSettings(exposedConfiguration.boostrapServers, it.toProperties())) }

    override fun configuration(): List<String> = context.options.configureExposedConfiguration(exposedConfiguration) + listOf(
        "kafka.bootstrapServers=${context.container.bootstrapServers}",
        "kafka.isSecure=false"
    )

    override suspend fun stop() {
        subscribeToAllConsumer.close()
        kafkaProducer.close()
        context.container.stop()
    }

    override fun close(): Unit = runBlocking { stop() }

    companion object {
        const val SubscribeToAllGroupId = "stove-kafka-subscribe-to-all"
    }
}

class StoveKafkaValueDeserializer<T : Any> : Deserializer<T> {
    private val jsonSerializer = StoveJacksonJsonSerializer()

    @Suppress("UNCHECKED_CAST")
    override fun deserialize(
        topic: String,
        data: ByteArray,
    ): T = jsonSerializer.deserialize<Any>(data) as T
}

class StoveKafkaValueSerializer<T : Any> : Serializer<T> {
    private val jsonSerializer = StoveJacksonJsonSerializer()
    override fun serialize(
        topic: String,
        data: T,
    ): ByteArray = jsonSerializer.serializeAsBytes(data)
}
