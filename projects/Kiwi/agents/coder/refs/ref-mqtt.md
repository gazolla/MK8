# MQTT Client — Eclipse Paho (JBang)

MQTT is a lightweight pub/sub protocol for IoT, home automation, and telemetry.
Eclipse Paho is the standard Java client.

---

## Connect, publish, subscribe

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//DEPS org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5
//SOURCES ../../../../kernel/KernelEvent.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginBase.java

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import java.io.OutputStream;
import java.util.Map;

public class MqttTool {

    static MqttClient mqttClient;
    static volatile OutputStream kernelOut;

    public static void main(String[] args) throws Exception {
        connectMqtt();
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, MqttTool::handle);
    }

    static void connectMqtt() throws Exception {
        String broker   = System.getenv().getOrDefault("MQTT_BROKER", "tcp://localhost:1883");
        String clientId = "mk7-" + java.util.UUID.randomUUID().toString().substring(0, 8);

        mqttClient = new MqttClient(broker, clientId, new MemoryPersistence());

        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        opts.setConnectionTimeout(10);
        opts.setKeepAliveInterval(30);
        opts.setAutomaticReconnect(true);

        // Optional credentials
        String user = System.getenv("MQTT_USER");
        String pass = System.getenv("MQTT_PASS");
        if (user != null) {
            opts.setUserName(user);
            opts.setPassword(pass != null ? pass.toCharArray() : new char[0]);
        }

        // Callback for incoming messages and connection events
        mqttClient.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                System.err.println("[MQTT] connection lost: " + cause.getMessage());
                // automatic reconnect handles reconnection
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                String payload = new String(message.getPayload());
                System.out.println("[MQTT] " + topic + " → " + payload);

                // Forward to MK8 kernel
                if (kernelOut != null) {
                    String eventPayload = KernelEvent.MAPPER.writeValueAsString(
                            Map.of("topic", topic, "payload", payload));
                    PluginBase.publish(
                            KernelEvent.of("system.mqtt.message", eventPayload, "mqtt-tool"),
                            kernelOut);
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // called when a published message is delivered
            }
        });

        mqttClient.connect(opts);
        System.out.println("[MQTT] Connected to " + broker);

        // Subscribe to topics on connect
        mqttClient.subscribe("sensors/#", 1); // QoS 1
    }

    static void handle(String json, OutputStream out) throws Exception {
        kernelOut = out;
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);
        if (!"capability.tool.mqtt.publish".equals(event.type())) return;

        try {
            JsonNode input   = KernelEvent.MAPPER.readTree(event.payload()).path("input");
            String topic     = input.path("topic").asText();
            String payload   = input.path("payload").asText();
            int    qos       = input.path("qos").asInt(1);
            boolean retained = input.path("retained").asBoolean(false);

            MqttMessage msg = new MqttMessage(payload.getBytes());
            msg.setQos(qos);
            msg.setRetained(retained);
            mqttClient.publish(topic, msg);

            PluginBase.publish(
                    KernelEvent.withCorrelation("capability.result",
                            KernelEvent.MAPPER.writeValueAsString(Map.of("result", "published")),
                            "mqtt-tool", event.correlationId(), event.sessionId()),
                    out);
        } catch (Exception e) {
            PluginBase.publish(
                    KernelEvent.withCorrelation("capability.error",
                            KernelEvent.MAPPER.writeValueAsString(Map.of("reason", e.getMessage())),
                            "mqtt-tool", event.correlationId(), event.sessionId()),
                    out);
        }
    }
}
```

---

## MQTT over TLS (port 8883)

```java
String broker = "ssl://broker.hivemq.com:8883";
MqttConnectOptions opts = new MqttConnectOptions();
opts.setSocketFactory(javax.net.ssl.SSLSocketFactory.getDefault());
// For custom CA cert, configure SSLContext with TrustManager
```

---

## QoS levels

| QoS | Guarantee | Use case |
|---|---|---|
| 0 | At most once (fire and forget) | sensor telemetry, high-frequency data |
| 1 | At least once (may duplicate) | commands, alerts |
| 2 | Exactly once (slowest) | billing, critical state changes |

---

## Topic wildcards

```
sensors/+/temperature    # + matches one level: sensors/room1/temperature
sensors/#                # # matches all sub-levels: sensors/room1/temp/celsius
```

---

## Retained messages — last known value

```java
// Publish a retained message — new subscribers immediately receive the last value
MqttMessage msg = new MqttMessage("25.3".getBytes());
msg.setRetained(true);
msg.setQos(1);
mqttClient.publish("sensors/room1/temperature", msg);
```
