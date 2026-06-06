# Kafka Producer and Consumer (JBang)

Apache Kafka client for event-driven integrations.
Requires a running Kafka broker (local or remote).

---

## Producer — publish messages

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//DEPS org.apache.kafka:kafka-clients:3.8.0
//SOURCES ../../../../kernel/KernelEvent.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginBase.java

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.*;

public class KafkaProducerTool {

    static KafkaProducer<String, String> producer;

    public static void main(String[] args) throws Exception {
        producer = createProducer(System.getenv().getOrDefault("KAFKA_BOOTSTRAP", "localhost:9092"));
        Runtime.getRuntime().addShutdownHook(new Thread(producer::close));
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, KafkaProducerTool::handle);
    }

    static KafkaProducer<String, String> createProducer(String bootstrap) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,  bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG,               "all");    // strongest durability
        props.put(ProducerConfig.RETRIES_CONFIG,            3);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new KafkaProducer<>(props);
    }

    static void handle(String json, OutputStream out) throws Exception {
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);
        if (!"capability.tool.kafka.publish".equals(event.type())) return;

        try {
            JsonNode input = KernelEvent.MAPPER.readTree(event.payload()).path("input");
            String topic   = input.path("topic").asText();
            String key     = input.path("key").asText(null);  // null = random partition
            String value   = input.path("value").asText();

            ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
            RecordMetadata meta = producer.send(record).get(5, TimeUnit.SECONDS);

            String result = KernelEvent.MAPPER.writeValueAsString(Map.of(
                    "result", Map.of(
                            "partition", meta.partition(),
                            "offset",    meta.offset(),
                            "topic",     meta.topic())));
            PluginBase.publish(
                    KernelEvent.withCorrelation("capability.result", result, "kafka-producer",
                            event.correlationId(), event.sessionId()), out);

        } catch (Exception e) {
            PluginBase.publish(
                    KernelEvent.withCorrelation("capability.error",
                            KernelEvent.MAPPER.writeValueAsString(Map.of("reason", e.getMessage())),
                            "kafka-producer", event.correlationId(), event.sessionId()), out);
        }
    }
}
```

---

## Consumer — subscribe and poll messages

```java
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import java.time.Duration;

static KafkaConsumer<String, String> createConsumer(String bootstrap, String groupId) {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,        bootstrap);
    props.put(ConsumerConfig.GROUP_ID_CONFIG,                 groupId);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,   StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,        "earliest"); // or "latest"
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,       false); // manual commit = safer
    return new KafkaConsumer<>(props);
}

// Poll loop — run in a background thread
static void startConsuming(String bootstrap, String groupId, String topic,
                           java.util.function.Consumer<ConsumerRecord<String, String>> onMessage) {
    Thread.ofVirtual().start(() -> {
        try (KafkaConsumer<String, String> consumer = createConsumer(bootstrap, groupId)) {
            consumer.subscribe(List.of(topic));
            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    try {
                        onMessage.accept(record);
                    } catch (Exception e) {
                        System.err.println("[KAFKA] processing error: " + e.getMessage());
                    }
                }
                if (!records.isEmpty()) consumer.commitSync(); // commit after processing
            }
        } catch (Exception e) {
            System.err.println("[KAFKA] consumer error: " + e.getMessage());
        }
    });
}

// Usage:
// startConsuming("localhost:9092", "my-group", "my-topic", record -> {
//     System.out.println("offset=" + record.offset() + " key=" + record.key() + " value=" + record.value());
// });
```

---

## Kafka with SASL/TLS (cloud brokers: Confluent, MSK, Redpanda)

```java
props.put("security.protocol",           "SASL_SSL");
props.put("sasl.mechanism",              "PLAIN");
props.put("sasl.jaas.config",
        "org.apache.kafka.common.security.plain.PlainLoginModule required " +
        "username=\"" + System.getenv("KAFKA_USER") + "\" " +
        "password=\"" + System.getenv("KAFKA_PASSWORD") + "\";");
```

---

## Key configuration tips

| Config | Value | Why |
|---|---|---|
| `acks=all` | durability | guarantees leader + replicas wrote |
| `enable.idempotence=true` | exactly-once | no duplicate messages on retry |
| `auto.offset.reset=earliest` | consumer | read from beginning if no stored offset |
| `enable.auto.commit=false` | consumer | commit only after successful processing |
| `max.poll.records=100` | consumer | limit batch size per poll |
