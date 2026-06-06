# Quarkus REST API (JBang)

Quarkus supports JBang natively via `@QuarkusMain`.
Starts fast, supports GraalVM native, Jakarta EE annotations.

---

## Minimal Quarkus REST service

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS io.quarkus.platform:quarkus-bom:3.15.1@pom
//DEPS io.quarkus:quarkus-rest
//DEPS io.quarkus:quarkus-rest-jackson

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

@QuarkusMain
public class MyService {
    public static void main(String... args) {
        Quarkus.run(args);
    }
}

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class MyResource {

    @GET
    @Path("/hello")
    public Map<String, String> hello() {
        return Map.of("message", "Hello from Quarkus!");
    }

    @POST
    @Path("/echo")
    public Map<String, Object> echo(Map<String, Object> body) {
        return Map.of("received", body);
    }

    @GET
    @Path("/items/{id}")
    public Map<String, Object> getById(@PathParam("id") String id) {
        return Map.of("id", id, "name", "Item " + id);
    }
}
```

---

## Quarkus + MK8: hybrid plugin

A system plugin that exposes a REST API AND connects to the MK8 kernel:

```java
@QuarkusMain
public class HybridPlugin {
    public static void main(String... args) throws Exception {
        // Start MK8 kernel connection in background thread
        Thread mk7Thread = new Thread(() -> {
            try {
                PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, HybridPlugin::handleEvent);
            } catch (Exception e) {
                System.err.println("MK8 error: " + e.getMessage());
            }
        });
        mk7Thread.setDaemon(true);
        mk7Thread.start();

        // Quarkus blocks the main thread
        Quarkus.run(args);
    }

    static void handleEvent(String json, java.io.OutputStream out) throws Exception {
        // handle MK8 events
    }
}
```

---

## Configuring port and properties

Create `application.properties` alongside the .java file:

```properties
quarkus.http.port=8081
quarkus.log.level=WARN
```

Or pass via system property:
```
jbang -Dquarkus.http.port=8081 MyService.java
```

---

## Key Quarkus BOM versions

| Quarkus | Java |
|---|---|
| `3.15.1` | 21+ |
| `3.8.x` (LTS) | 17+ |

Always use `@pom` on the BOM dep: `io.quarkus.platform:quarkus-bom:3.15.1@pom`
