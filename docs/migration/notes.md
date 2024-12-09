# Migration Notes

## From 0.14.x to 0.15.x

### Breaking Changes

The most notable breaking change is ser/de operations. The framework was only relying on Jackson for serialization and
deserialization. Now, it provides a way to use other serialization libraries. `StoveSerde<TIn, TOut>` is the new interface
that you can implement to provide your own serialization and deserialization logic.

`StoveSerde` also provides the access to the other serializers that `com-trendyol:stove-testing-e2e` package has.

* Jackson
* Gson
* Kotlinx

Also look at ser/de section: [Serialization and Deserialization](../index.md#serializing-and-deserializing)

#### Spring Kafka (com-trendyol:stove-spring-testing-e2e-kafka)

The `TestSystemKafkaInterceptor` now depends on `StoveSerde` to provide the serialization and deserialization logic instead of `ObjectMapper`.

You can of course use your default Jackson implementation by providing the `ObjectMapperConfig.default()` to the `StoveSerde.jackson.anyByteArraySerde` function.

```kotlin
class TestSystemInitializer : BaseApplicationContextInitializer({
  bean<TestSystemKafkaInterceptor<*, *>>(isPrimary = true)
  bean { StoveSerde.jackson.anyByteArraySerde(ObjectMapperConfig.default()) } // or any other serde that is <Any, ByteArray>
})
```

### Standalone Kafka

```kotlin
kafka {
  KafkaSystemOptions(
    serde = StoveSerde.jackson.anyByteArraySerde(ObjectMapperConfig.default) // or any other serde that is <Any, ByteArray>
    //...
  )
}
```

### Couchbase

```kotlin
couchbase {
  CouchbaseSystemOptions(
    clusterSerDe = JacksonJsonSerializer(CouchbaseConfiguration.objectMapper), // here you can provide your own serde
    //...
  )
}
```

### Http

```kotlin
 httpClient {
  HttpClientSystemOptions(
    baseUrl = "http://localhost:8001",
    contentConverter = JacksonConverter(ObjectMapperConfig.default)
  )
}
```

### Wiremock

```kotlin
wiremock {
  WireMockSystemOptions(
    port = 9090,
    serde = StoveSerde.jackson.anyByteArraySerde(ObjectMapperConfiguration.default)
  )
```

### Elasticsearch

```kotlin
elasticsearch {
  ElasticsearchSystemOptions(
    jsonpMapper = JacksonJsonpMapper(StoveSerde.jackson.default), // or any JsonpMapper
  )
}
```

### Mongodb

```kotlin
mongodb {
  MongoDbSystemOptions(
    serde = StoveSerde.jackson.default // or any other serde that you implement
  )
}
```

The default serde is:
```kotlin
  val serde: StoveSerde<Any, String> = StoveSerde.jackson.anyJsonStringSerde(
    StoveSerde.jackson.byConfiguring {
      disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      enable(MapperFeature.DEFAULT_VIEW_INCLUSION)
      addModule(ObjectIdModule())
      addModule(KotlinModule.Builder().build())
    }
  ),
```
