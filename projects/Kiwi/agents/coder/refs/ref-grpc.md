# gRPC Client (JBang)

gRPC requires generated stub classes from `.proto` files.
JBang cannot run `protoc` automatically — stubs must be generated beforehand.
For dynamic calls without stubs, see the grpc-reflection approach below.

---

## Step 0 — generate stubs (one-time, outside JBang)

```bash
# Install protoc + gRPC Java plugin, then:
protoc --java_out=. --grpc-java_out=. --proto_path=. service.proto

# Or use Buf (modern alternative):
buf generate
```

Place the generated `.java` files alongside your JBang script so `//SOURCES` can include them.

---

## gRPC client with generated stubs

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//DEPS io.grpc:grpc-netty-shaded:1.68.0
//DEPS io.grpc:grpc-protobuf:1.68.0
//DEPS io.grpc:grpc-stub:1.68.0
//DEPS com.google.protobuf:protobuf-java:4.28.2
//SOURCES ../../../../kernel/KernelEvent.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginBase.java
// If stubs are in the same directory:
//SOURCES MyServiceGrpc.java
//SOURCES MyRequest.java
//SOURCES MyResponse.java

import io.grpc.*;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.*;

public class GrpcClientTool {

    static ManagedChannel channel;
    // Replace with your generated stub class:
    // static MyServiceGrpc.MyServiceBlockingStub stub;

    public static void main(String[] args) throws Exception {
        channel = ManagedChannelBuilder
                .forAddress("localhost", 50051)
                .usePlaintext()           // remove for TLS
                .build();
        // stub = MyServiceGrpc.newBlockingStub(channel);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            channel.shutdownNow();
        }));

        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, GrpcClientTool::handle);
    }

    static void handle(String json, OutputStream out) throws Exception {
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);
        if (!"capability.tool.grpc.call".equals(event.type())) return;

        try {
            JsonNode input = KernelEvent.MAPPER.readTree(event.payload()).path("input");
            // Build request using generated protobuf builder:
            // MyRequest request = MyRequest.newBuilder()
            //         .setField(input.path("field").asText())
            //         .build();
            // MyResponse response = stub.myMethod(request);
            // String result = response.getResult();

            // Placeholder:
            String result = "grpc call not yet wired to stub";
            PluginBase.publish(
                    KernelEvent.withCorrelation("capability.result",
                            KernelEvent.MAPPER.writeValueAsString(Map.of("result", result)),
                            "grpc-client", event.correlationId(), event.sessionId()),
                    out);
        } catch (StatusRuntimeException e) {
            String reason = "gRPC error: " + e.getStatus().getCode() + ": " + e.getStatus().getDescription();
            PluginBase.publish(
                    KernelEvent.withCorrelation("capability.error",
                            KernelEvent.MAPPER.writeValueAsString(Map.of("reason", reason)),
                            "grpc-client", event.correlationId(), event.sessionId()),
                    out);
        }
    }
}
```

---

## TLS / mTLS channel

```java
// TLS (server cert from system trust store)
channel = ManagedChannelBuilder
        .forAddress("api.example.com", 443)
        .build(); // TLS is used automatically when no usePlaintext()

// mTLS (custom CA / client cert)
channel = NettyChannelBuilder.forAddress("api.example.com", 443)
        .sslContext(GrpcSslContexts.forClient()
                .trustManager(new File("ca.crt"))
                .keyManager(new File("client.crt"), new File("client.key"))
                .build())
        .build();
```

---

## Deadline / timeout per call

```java
MyResponse response = stub
        .withDeadlineAfter(5, TimeUnit.SECONDS)
        .myMethod(request);
```

---

## Async stub (non-blocking)

```java
// MyServiceGrpc.MyServiceStub asyncStub = MyServiceGrpc.newStub(channel);
asyncStub.myMethod(request, new StreamObserver<MyResponse>() {
    @Override public void onNext(MyResponse value) {
        System.out.println("Got: " + value.getResult());
    }
    @Override public void onError(Throwable t) {
        System.err.println("Error: " + t.getMessage());
    }
    @Override public void onCompleted() {
        System.out.println("Stream done");
    }
});
```

---

## gRPC server reflection — dynamic calls without stubs

For calling a service whose `.proto` you don't have, use `grpc-reflection` + `grpcurl` as a subprocess (see `ref-process.md`):

```java
// Discover available services
String services = run("grpcurl", "-plaintext", "localhost:50051", "list");

// Describe a method
String desc = run("grpcurl", "-plaintext", "localhost:50051", "describe", "mypackage.MyService.MyMethod");

// Call a method with JSON input
String result = run("grpcurl", "-plaintext", "-d",
        "{\"field\": \"value\"}",
        "localhost:50051",
        "mypackage.MyService/MyMethod");
```

This avoids proto compilation entirely and works with any gRPC server that has reflection enabled.

---

## Common gRPC status codes

| Code | Meaning |
|---|---|
| `OK` | Success |
| `NOT_FOUND` | Resource does not exist |
| `UNAUTHENTICATED` | Missing or invalid credentials |
| `PERMISSION_DENIED` | Valid credentials, insufficient permissions |
| `UNAVAILABLE` | Server down or overloaded — safe to retry |
| `DEADLINE_EXCEEDED` | Call took too long |
| `INVALID_ARGUMENT` | Bad input |
