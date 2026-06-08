// Shared file — included via //SOURCES by any plugin that wants leveled logging.
// No JBang header (not an entry point).

import java.io.OutputStream;
import java.util.Map;

/**
 * Log — Minimal leveled logging API with two pluggable sinks.
 *
 * Configured once per process via {@link #configure}. Every call always writes to
 * the local stdout/stderr (so the per-process log file stays populated, exactly as
 * before), and — when a bus OutputStream is supplied — ALSO publishes a {@code log.{level}}
 * event so a LogInterceptor in the kernel can consolidate logs from every process into
 * a single sink. The bus path is opt-in: with {@code busOut == null}, Log is pure stdout.
 *
 * Design notes:
 *   - One level threshold (minLevel) applies to both sinks. Default INFO.
 *   - Logging must never throw or block the caller meaningfully: the bus write only
 *     serializes a small frame (the heavy work — file IO — happens in the interceptor,
 *     asynchronously). All exceptions are swallowed.
 *   - Frame writes synchronize on the same OutputStream lock PluginBase.publish uses,
 *     so log frames never interleave with normal event frames.
 */
public final class Log {

    public enum Level { DEBUG, INFO, WARN, ERROR }

    private static volatile Level        minLevel = Level.INFO;
    private static volatile String       source   = "?";
    private static volatile OutputStream busOut   = null;   // null = stdout only

    private Log() {}

    /** Set this process's log source (e.g. plugin id) and optional bus sink (null = stdout only). */
    public static void configure(String source, OutputStream busOut) {
        Log.source = source;
        Log.busOut = busOut;
    }

    public static void level(Level min) { minLevel = min; }

    public static void debug(String msg) { emit(Level.DEBUG, msg); }
    public static void info (String msg) { emit(Level.INFO,  msg); }
    public static void warn (String msg) { emit(Level.WARN,  msg); }
    public static void error(String msg) { emit(Level.ERROR, msg); }

    /**
     * Raw variants — print the line exactly as given (no "[source]" prefix), then
     * also ship it to the bus. Use these to migrate existing fully-formatted prints
     * (e.g. {@code System.out.println("[ID] ...")}) as a 1:1 swap: stdout output stays
     * byte-for-byte identical, and the line additionally reaches the consolidated log.
     */
    public static void rawInfo (String line) { raw(Level.INFO,  line); }
    public static void rawError(String line) { raw(Level.ERROR, line); }

    private static void emit(Level level, String msg) {
        boolean err = level == Level.ERROR || level == Level.WARN;
        String line = "[" + source + "]" + (err ? " " + level + ":" : "") + " " + msg;
        raw(level, line);
    }

    private static void raw(Level level, String line) {
        if (level.ordinal() < minLevel.ordinal()) return;

        // 1) Local stdout/stderr — keeps the per-process log file populated.
        if (level == Level.ERROR || level == Level.WARN) System.err.println(line);
        else System.out.println(line);

        // 2) Consolidated bus sink — opt-in.
        OutputStream out = busOut;
        if (out != null) publishToBus(level, line, out);
    }

    private static void publishToBus(Level level, String msg, OutputStream out) {
        try {
            String payload = KernelEvent.MAPPER.writeValueAsString(Map.of(
                    "level", level.name(), "source", source, "message", msg));
            String json = KernelEvent.MAPPER.writeValueAsString(
                    KernelEvent.of("log." + level.name().toLowerCase(), payload, source));
            synchronized (out) { KernelEvent.writeFrame(out, json); }
        } catch (Exception ignored) {
            // Logging must never throw — drop the bus copy; the stdout copy already happened.
        }
    }
}
