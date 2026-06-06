# Sockets — TCP, UDP, Unix Domain Sockets (JBang)

All socket types are built into the JDK — no //DEPS needed.
Java 16+ has native Unix Domain Socket support via `UnixDomainSocketAddress`.

---

## TCP Server (NIO non-blocking)

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../../kernel/KernelEvent.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginBase.java

import java.io.*;
import java.net.*;
import java.util.concurrent.Executors;

public class TcpServerTool {

    static final int PORT = 9090;

    public static void main(String[] args) throws Exception {
        // Accept clients in a background thread pool
        var executor = Executors.newVirtualThreadPerTaskExecutor(); // Java 21 virtual threads
        try (ServerSocket server = new ServerSocket(PORT)) {
            System.out.println("[TCP] Listening on port " + PORT);
            while (true) {
                Socket client = server.accept();
                executor.submit(() -> handleClient(client));
            }
        }
    }

    static void handleClient(Socket client) {
        try (client;
             var in  = new BufferedReader(new InputStreamReader(client.getInputStream()));
             var out = new PrintWriter(client.getOutputStream(), true)) {
            String line;
            while ((line = in.readLine()) != null) {
                System.out.println("[TCP] received: " + line);
                out.println("echo: " + line);
            }
        } catch (IOException e) {
            System.err.println("[TCP] client error: " + e.getMessage());
        }
    }
}
```

---

## TCP Client

```java
try (Socket socket = new Socket("localhost", 9090);
     var out = new PrintWriter(socket.getOutputStream(), true);
     var in  = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

    out.println("Hello, server!");
    String response = in.readLine();
    System.out.println("Server replied: " + response);
}
```

---

## UDP (Datagram)

```java
// UDP sender
try (DatagramSocket socket = new DatagramSocket()) {
    byte[] data = "hello".getBytes();
    DatagramPacket packet = new DatagramPacket(
            data, data.length,
            InetAddress.getByName("localhost"), 9091);
    socket.send(packet);
}

// UDP receiver
try (DatagramSocket socket = new DatagramSocket(9091)) {
    byte[] buf = new byte[1024];
    DatagramPacket packet = new DatagramPacket(buf, buf.length);
    socket.receive(packet);
    String msg = new String(packet.getData(), 0, packet.getLength());
    System.out.println("Received: " + msg);
}
```

---

## Unix Domain Socket — client (same as MK8 kernel connection)

```java
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;

UnixDomainSocketAddress address = UnixDomainSocketAddress.of(Path.of("/tmp/mk7/kernel.sock"));
try (SocketChannel channel = SocketChannel.open(address)) {
    // Write length-prefixed frame (4 bytes big-endian + JSON payload)
    byte[] payload = "{\"type\":\"ping\"}".getBytes();
    ByteBuffer header = ByteBuffer.allocate(4);
    header.putInt(payload.length);
    header.flip();
    channel.write(header);
    channel.write(ByteBuffer.wrap(payload));

    // Read response header
    ByteBuffer respHeader = ByteBuffer.allocate(4);
    channel.read(respHeader);
    respHeader.flip();
    int length = respHeader.getInt();

    // Read response body
    ByteBuffer respBody = ByteBuffer.allocate(length);
    channel.read(respBody);
    String json = new String(respBody.array(), 0, length);
    System.out.println("Response: " + json);
}
```

---

## Unix Domain Socket — server

```java
import java.net.UnixDomainSocketAddress;
import java.nio.channels.*;
import java.nio.file.*;

Path socketPath = Path.of("/tmp/my-service.sock");
Files.deleteIfExists(socketPath); // remove stale socket

UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
    server.bind(address);
    System.out.println("[UDS] Listening on " + socketPath);
    while (true) {
        SocketChannel client = server.accept();
        // handle client in a thread
        Thread.ofVirtual().start(() -> handleUdsClient(client));
    }
}
```

---

## Virtual threads (Java 21) — best practice for socket servers

```java
// One virtual thread per client connection — scales to thousands of connections
var executor = Executors.newVirtualThreadPerTaskExecutor();
executor.submit(() -> handleClient(client));

// Or directly
Thread.ofVirtual().start(() -> handleClient(client));
```
