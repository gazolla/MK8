# Spring Boot REST API (JBang)

Spring Boot works with JBang via a single `//DEPS` line.
Auto-configures embedded Tomcat, Jackson, and all Spring MVC infrastructure.

---

## Minimal Spring Boot REST service

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS org.springframework.boot:spring-boot-starter-web:3.3.5

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@SpringBootApplication
@RestController
public class MyService {

    public static void main(String[] args) {
        SpringApplication.run(MyService.class, args);
    }

    @GetMapping("/api/hello")
    public Map<String, String> hello() {
        return Map.of("message", "Hello from Spring Boot!");
    }

    @PostMapping("/api/echo")
    public Map<String, Object> echo(@RequestBody Map<String, Object> body) {
        return Map.of("received", body);
    }

    @GetMapping("/api/items/{id}")
    public Map<String, Object> getById(@PathVariable String id) {
        return Map.of("id", id, "name", "Item " + id);
    }

    @DeleteMapping("/api/items/{id}")
    public Map<String, String> delete(@PathVariable String id) {
        return Map.of("deleted", id);
    }
}
```

---

## Spring Boot + MK8: hybrid plugin

```java
@SpringBootApplication
@RestController
public class HybridPlugin {

    public static void main(String[] args) throws Exception {
        // Start MK8 kernel connection in a daemon thread
        Thread mk7Thread = new Thread(() -> {
            try {
                PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, HybridPlugin::handleEvent);
            } catch (Exception e) {
                System.err.println("MK8 error: " + e.getMessage());
            }
        });
        mk7Thread.setDaemon(true);
        mk7Thread.start();

        // Spring Boot blocks the main thread
        SpringApplication.run(HybridPlugin.class, args);
    }

    @GetMapping("/api/status")
    public Map<String, String> status() {
        return Map.of("status", "running");
    }

    static void handleEvent(String json, java.io.OutputStream out) throws Exception {
        // handle MK8 events
    }
}
```

---

## Configuring port

Via `application.properties` alongside the .java file:
```properties
server.port=8081
logging.level.root=WARN
```

Or via system property on command line:
```
jbang -Dserver.port=8081 MyService.java
```

---

## Adding Spring Data (JPA + H2)

```java
//DEPS org.springframework.boot:spring-boot-starter-web:3.3.5
//DEPS org.springframework.boot:spring-boot-starter-data-jpa:3.3.5
//DEPS com.h2database:h2:2.2.224
```

---

## Key Spring Boot 3.x versions

| Starter | Purpose |
|---|---|
| `spring-boot-starter-web:3.3.5` | REST + embedded Tomcat |
| `spring-boot-starter-webflux:3.3.5` | Reactive REST (Netty) |
| `spring-boot-starter-data-jpa:3.3.5` | JPA + Hibernate |
| `spring-boot-starter-security:3.3.5` | Auth + authorization |

Spring Boot 3.x requires Java 17+. With `//JAVA 21+` in JBang it works fine.
