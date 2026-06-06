# External Processes — ProcessBuilder (JBang)

No extra //DEPS needed. Use to run shell commands, CLI tools, or child JVM processes.

---

## Run a command and capture output

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../../kernel/KernelEvent.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginBase.java

import java.io.*;
import java.util.List;
import java.util.Map;

/**
 * Runs a command and returns stdout as a String.
 * Throws RuntimeException if exit code != 0 (includes stderr in the message).
 */
static String run(String... command) throws Exception {
    Process p = new ProcessBuilder(command)
            .redirectErrorStream(true) // merge stderr into stdout
            .start();

    String output = new String(p.getInputStream().readAllBytes());
    int exit = p.waitFor();

    if (exit != 0)
        throw new RuntimeException("Command failed (exit=" + exit + "): " + output.trim());

    return output.trim();
}

// Example usage:
// String version = run("java", "--version");
// String listing = run("ls", "-la", "/tmp");
// String result  = run("python3", "script.py", "--input", value);
```

---

## Separate stdout and stderr

```java
static Map<String, String> runWithStreams(String... command) throws Exception {
    Process p = new ProcessBuilder(command)
            .redirectErrorStream(false) // keep stderr separate
            .start();

    // Read both streams concurrently to avoid blocking on full pipe buffers
    var stdoutFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
        try { return new String(p.getInputStream().readAllBytes()); }
        catch (IOException e) { return ""; }
    });
    var stderrFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
        try { return new String(p.getErrorStream().readAllBytes()); }
        catch (IOException e) { return ""; }
    });

    int exit   = p.waitFor();
    String out = stdoutFuture.join();
    String err = stderrFuture.join();

    if (exit != 0)
        throw new RuntimeException("Command failed (exit=" + exit + ")\nstdout: " + out + "\nstderr: " + err);

    return Map.of("stdout", out.trim(), "stderr", err.trim());
}
```

---

## Pipe input to stdin

```java
static String runWithInput(String input, String... command) throws Exception {
    Process p = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .start();

    // Write to stdin then close it — many programs wait for EOF
    try (var writer = new OutputStreamWriter(p.getOutputStream())) {
        writer.write(input);
    }

    String output = new String(p.getInputStream().readAllBytes());
    int exit = p.waitFor();

    if (exit != 0)
        throw new RuntimeException("Command failed (exit=" + exit + "): " + output.trim());

    return output.trim();
}

// Example: pipe JSON to jq
// String filtered = runWithInput(jsonString, "jq", ".items[].name");
```

---

## Timeout — kill if too slow

```java
import java.util.concurrent.TimeUnit;

static String runWithTimeout(int timeoutSeconds, String... command) throws Exception {
    Process p = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .start();

    boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
    if (!finished) {
        p.destroyForcibly();
        throw new RuntimeException("Command timed out after " + timeoutSeconds + "s: "
                + String.join(" ", command));
    }

    String output = new String(p.getInputStream().readAllBytes());
    if (p.exitValue() != 0)
        throw new RuntimeException("Command failed (exit=" + p.exitValue() + "): " + output.trim());

    return output.trim();
}
```

---

## Set working directory and environment variables

```java
Process p = new ProcessBuilder("npm", "run", "build")
        .directory(new File("/path/to/project"))  // working directory
        .environment().put("NODE_ENV", "production"); // WRONG — environment() returns a Map
// Correct approach:
ProcessBuilder pb = new ProcessBuilder("npm", "run", "build")
        .directory(new File("/path/to/project"));
pb.environment().put("NODE_ENV", "production");
pb.environment().put("PATH", pb.environment().get("PATH") + ":/usr/local/bin");
Process p = pb.start();
```

---

## Long-running background process (daemon-style)

```java
static Process startDaemon(String... command) throws Exception {
    Process p = new ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT) // print to our stdout
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();

    // Log when it exits unexpectedly
    Thread.ofVirtual().start(() -> {
        try {
            int code = p.waitFor();
            System.err.println("[PROC] Process exited with code " + code);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    });

    return p; // caller holds the reference; call p.destroy() to stop
}
```

---

## Shell execution (bash -c "...")

```java
// For complex shell commands with pipes, redirects, globbing:
String output = run("bash", "-c", "ls -la /tmp | grep log | wc -l");

// On Windows use cmd instead:
// String output = run("cmd", "/c", "dir /b C:\\logs");
```

---

## Critical rules

- **Never concatenate user input into command arrays** — each argument must be a separate array element. `new ProcessBuilder("ls", userInput)` is safe; `new ProcessBuilder("ls " + userInput)` is a shell injection vulnerability.
- **Always read both stdout and stderr** — if you only read one, the other can fill its buffer and deadlock the process.
- **Always call `waitFor()`** — not calling it leaks the process.
