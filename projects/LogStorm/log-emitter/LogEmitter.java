///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../kernel/KernelEvent.java
//SOURCES ../../../kernel/Log.java
//SOURCES ../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../kernel/interceptors/plugin/PluginBase.java

import com.fasterxml.jackson.databind.JsonNode;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * LogEmitter — Generates synthetic log events at variable rate.
 *
 * Starts paused. Play/Stop are controlled by storm.control events published
 * by the Dashboard. Emits logs in waves (20 → 50 → 100 → 200 → back) and
 * intentionally re-sends the same correlationId 30% of the time to exercise
 * IdempotencyInterceptor's cache and Single-Flight collapsing.
 */
public class LogEmitter {

    // ── Synthetic data ────────────────────────────────────────────────────────

    final String[] SERVICES = {
        "svc-auth", "svc-api", "svc-db", "svc-cache", "svc-queue", "svc-worker"
    };

    final String[][] MESSAGES = {
        { "DEBUG",  "cache lookup key={key}"                    },
        { "DEBUG",  "connection pool size={val}"                },
        { "INFO",   "GET /users 200 in {val}ms"                 },
        { "INFO",   "user {key} authenticated"                  },
        { "INFO",   "job {key} completed successfully"          },
        { "WARN",   "retry attempt {val}/3 for {key}"           },
        { "WARN",   "slow query {val}ms above threshold"        },
        { "WARN",   "memory usage at {val}%"                    },
        { "ERROR",  "timeout connecting to {key}"               },
        { "ERROR",  "null pointer in method {key}"              },
        { "FATAL",  "connection refused at {key}"               },
        { "FATAL",  "out of memory in process {key}"            }
    };

    // Wave pattern (logs/sec), cycles every WAVE_INTERVAL_MS
    final int[]  WAVE_RATES    = { 20, 50, 100, 200, 100, 50 };
    final long   WAVE_INTERVAL = 8_000;

    // ── State ─────────────────────────────────────────────────────────────────

    final PluginConfig   config;
    final Random         rng        = new Random();
    final AtomicBoolean  active       = new AtomicBoolean(false);
    final AtomicLong     seq          = new AtomicLong(0);
    final AtomicInteger  waveIndex    = new AtomicInteger(0);
    final AtomicInteger  rateOverride = new AtomicInteger(0); // 0 = follow wave

    volatile OutputStream kernelOut;
    volatile String       lastCorrId = UUID.randomUUID().toString();

    // ── Bootstrap ─────────────────────────────────────────────────────────────

    LogEmitter() throws Exception {
        config = PluginConfig.load("plugin.json");
    }

    public static void main(String[] args) throws Exception {
        KernelEvent.initLogging();
        new LogEmitter().start();
    }

    void start() throws Exception {
        Log.rawInfo("[EMITTER] Starting (paused — waiting for Play)...");
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, this::handle);
    }

    // ── Event handling ────────────────────────────────────────────────────────

    void handle(String json, OutputStream out) throws Exception {
        this.kernelOut = out;
        Log.configure(config.id(), out);
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);
        switch (event.type()) {
            case "storm.control"     -> handleControl(event);
            case "capability.result" -> { /* result acknowledged, no action needed */ }
        }
    }

    void handleControl(KernelEvent event) throws Exception {
        JsonNode p      = KernelEvent.MAPPER.readTree(event.payload());
        String   action = p.path("action").asText();
        if ("start".equals(action))  onStart();
        if ("stop".equals(action))   onStop();
        if ("rate".equals(action)) {
            int val = p.path("value").asInt(0);
            rateOverride.set(val);
            Log.rawInfo("[EMITTER] Rate override → " + val + " logs/sec");
        }
    }

    void onStart() {
        if (active.compareAndSet(false, true)) {
            Log.rawInfo("[EMITTER] Started.");
            Thread.ofVirtual().start(this::emitLoop);
            Thread.ofVirtual().start(this::waveLoop);
        }
    }

    void onStop() {
        if (active.compareAndSet(true, false))
            Log.rawInfo("[EMITTER] Stopped.");
    }

    // ── Emission loops ────────────────────────────────────────────────────────

    void emitLoop() {
        while (active.get()) {
            emit();
            int override = rateOverride.get();
            int r = override > 0 ? override : WAVE_RATES[waveIndex.get() % WAVE_RATES.length];
            try { Thread.sleep(Math.max(5, 1000 / r)); } catch (InterruptedException e) { break; }
        }
    }

    void waveLoop() {
        while (active.get()) {
            try { Thread.sleep(WAVE_INTERVAL); } catch (InterruptedException e) { break; }
            if (!active.get()) break;
            int next = waveIndex.incrementAndGet() % WAVE_RATES.length;
            Log.rawInfo("[EMITTER] Wave → " + WAVE_RATES[next] + " logs/sec");
        }
    }

    void emit() {
        if (kernelOut == null) return;
        try {
            String[] entry   = MESSAGES[rng.nextInt(MESSAGES.length)];
            String   level   = entry[0];
            String   message = entry[1]
                    .replace("{key}", rng.nextInt(1000) + "-" + pick(SERVICES))
                    .replace("{val}", String.valueOf(rng.nextInt(500)));
            String   service = pick(SERVICES);

            // 30% chance to reuse last corrId → exercises idempotency cache / collapsing
            String corrId = (rng.nextDouble() < 0.30 && seq.get() > 5)
                    ? lastCorrId
                    : UUID.randomUUID().toString();
            lastCorrId = corrId;

            String payload = KernelEvent.MAPPER.writeValueAsString(Map.of(
                    "name",    "log.process",
                    "service", service,
                    "level",   level,
                    "message", message,
                    "seq",     seq.incrementAndGet()
            ));

            PluginBase.publish(
                    KernelEvent.withCorrelation("capability.invoke", payload,
                            config.id(), corrId, "storm-session"),
                    kernelOut);

        } catch (Exception e) {
            Log.rawError("[EMITTER] Error: " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    String pick(String[] arr) { return arr[rng.nextInt(arr.length)]; }
}
