///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//DEPS dev.langchain4j:langchain4j:0.36.2
//DEPS dev.langchain4j:langchain4j-open-ai:0.36.2
//DEPS org.slf4j:slf4j-simple:2.0.9
//DEPS org.xerial:sqlite-jdbc:3.46.1.3
//SOURCES ../kernel/KernelEvent.java
//SOURCES ../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../kernel/interceptors/plugin/PluginBase.java
//SOURCES AgentConfig.java
//SOURCES SkillLoader.java
//SOURCES SessionStore.java
//SOURCES AgentCore.java
//SOURCES MissionRunner.java
//SOURCES CapabilityRouter.java
//SOURCES BlackboardClient.java

import java.io.OutputStream;
import java.util.concurrent.Executors;

/**
 * Agent — JBang entry point for all MK8 agents (structural runtime, shared by every
 * project's agent personas).
 *
 * Static shell only: main() initialises AgentCore and the event loop;
 * handle() delegates every incoming frame to AgentCore.dispatch().
 *
 * Launch with the persona directory as the first argument, e.g.:
 *   jbang agent/Agent.java projects/ChatAI/agents/assistant
 *
 * The persona directory holds only plugin.json + persona/skill .md files (no .java).
 *
 * A custom event loop is used instead of PluginBase.run() because the agent needs a
 * post-connect startup hook (core.start) to register capabilities and open the
 * SQLite session store before the read loop begins.
 */
public class Agent {

    static AgentCore core;

    public static void main(String[] args) throws Exception {
        KernelEvent.initLogging();
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
        core = new AgentCore(args);
        PluginBoot.connectAndRun(KernelEvent.DEFAULT_SOCKET, core.config.plugin(), (in, out) -> {
            core.start(out);
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            String json;
            while ((json = KernelEvent.readFrame(in)) != null) {
                final String j = json;
                executor.submit(() -> handle(j, out));
            }
        });
    }

    static void handle(String json, OutputStream out) {
        core.dispatch(json, out);
    }
}
