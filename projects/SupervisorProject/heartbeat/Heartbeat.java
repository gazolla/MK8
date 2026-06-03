///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../kernel/KernelEvent.java
//SOURCES ../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../kernel/interceptors/plugin/PluginBase.java

import java.io.OutputStream;

/**
 * Heartbeat — A deliberately idle persistent plugin used to exercise the
 * PluginInterceptor's supervision (crash detection + auto-restart).
 *
 * It connects, registers, and does nothing else — just stays alive. When its
 * process is killed (simulating a crash), the kernel announces the dropped
 * connection and the PluginInterceptor revives it from its launch command.
 * When the kernel itself dies, the PluginBase event loop sees EOF and exits,
 * so no orphan survives.
 */
public class Heartbeat {

    public static void main(String[] args) throws Exception {
        KernelEvent.initLogging();
        System.out.println("[HEARTBEAT] Starting (pid=" + ProcessHandle.current().pid() + ")...");
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, Heartbeat::handle);
    }

    static void handle(String json, OutputStream out) {
        // Idle on purpose — nothing to do.
    }
}
