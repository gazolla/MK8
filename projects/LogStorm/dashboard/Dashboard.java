///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//DEPS org.openjfx:javafx-controls:21.0.2
//DEPS org.openjfx:javafx-graphics:21.0.2
//DEPS org.openjfx:javafx-base:21.0.2
//SOURCES ../../../kernel/KernelEvent.java
//SOURCES ../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../kernel/interceptors/plugin/PluginBase.java

import com.fasterxml.jackson.databind.JsonNode;
import javafx.animation.*;
import javafx.application.*;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.canvas.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.*;
import javafx.stage.*;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class Dashboard extends Application {

    // ── Inner: particle travelling along the event bus path ──────────────────

    static class Particle {
        final double fx, fy, tx, ty;
        final Color  color;
        final long   startMs, durMs;

        Particle(double fx, double fy, double tx, double ty, Color c, long start, long dur) {
            this.fx = fx; this.fy = fy; this.tx = tx; this.ty = ty;
            color = c; startMs = start; durMs = dur;
        }

        boolean active() { long e = elapsed(); return e >= 0 && e < durMs; }
        boolean done()   { return elapsed() >= durMs; }
        long elapsed()   { return System.currentTimeMillis() - startMs; }

        double[] pos() {
            double t = Math.min(1.0, Math.max(0, (double) elapsed() / durMs));
            t = t * t * (3 - 2 * t); // smoothstep
            return new double[]{ fx + t * (tx - fx), fy + t * (ty - fy) };
        }
    }

    // ── Inner: plugin node displayed on the canvas ───────────────────────────

    record PluginNode(String id, String label, double x, double y, String hex) {}

    // ── Colours ───────────────────────────────────────────────────────────────

    static final String BG_ROOT = "#0d1117";
    static final String BG_CARD = "#161b22";
    static final String FG_MAIN = "#e6edf3";
    static final String FG_DIM  = "#8b949e";
    static final String C_GREEN = "#3fb950";
    static final String C_BLUE  = "#58a6ff";
    static final String C_AMBER = "#d29922";
    static final String C_PLAY  = "#238636";
    static final String C_STOP  = "#da3633";

    static final Map<String, String> LEVEL_COLOR = Map.of(
            "DEBUG", "#6e7681",
            "INFO",  "#3fb950",
            "WARN",  "#d29922",
            "ERROR", "#f85149",
            "FATAL", "#ff7b72");

    // ── Canvas layout ─────────────────────────────────────────────────────────

    static final double BUS_Y    = 108;
    static final double CANVAS_W = 760;
    static final double CANVAS_H = 215;
    static final double NODE_R   = 22;

    // proc-thorough → centre (430), proc-fast → right (660)
    // dashboard ↔ capability swap: dashboard → right (660), capability → centre (430)
    final List<PluginNode> NODES = List.of(
            new PluginNode("log-emitter",        "log-emitter",   100, 38,  C_GREEN),
            new PluginNode("processor-thorough", "proc-thorough", 430, 38,  C_BLUE),
            new PluginNode("processor-fast",     "proc-fast",     660, 38,  C_BLUE),
            new PluginNode("idempotency",        "idempotency",   100, 177, C_AMBER),
            new PluginNode("capability",         "capability",    430, 177, C_AMBER),
            new PluginNode("dashboard",          "dashboard",     660, 177, "#bc8cff")
    );

    // ── Info tab text ─────────────────────────────────────────────────────────

    static final String INFO_TEXT = """
╔══════════════════════════════════════════════════════════════════════════╗
║                    LogStorm — MK8 MicroKernel Load Test                  ║
╚══════════════════════════════════════════════════════════════════════════╝

OBJECTIVE
────────────────────────────────────────────────────────────────────────────
LogStorm exercises the MK8 MicroKernel under concurrent load and validates
four key runtime features simultaneously:

  • Bidding Auctions — two processor instances compete for every log event.
    processor-fast (bidWeight=1.5) wins most rounds; processor-thorough
    (bidWeight=0.8) wins when fast is under higher load. Effective score =
    bidWeight × (1 − load). The Auction Wins card shows the live split.

  • On-Demand Lifecycle — both processors start dead. PluginInterceptor spawns
    them via ProcessBuilder on the first invocation and kills them after
    60 seconds of idle time. Watch spawn events in the animation.

  • Idempotency Cache — 30% of logs intentionally reuse the last
    correlationId. When that ID is already cached, capability.result is
    returned in <1ms without touching any plugin process.

  • Single-Flight Collapsing — if a duplicate corrId arrives while the
    original is still in-flight, IdempotencyInterceptor collapses the
    duplicate. Only ONE processor execution occurs; both callers receive
    the result simultaneously once it completes.

PLUGINS
────────────────────────────────────────────────────────────────────────────
  log-emitter          system  /  persistent
    Generates synthetic log lines (service, level, message) in wave
    patterns: 20 → 50 → 100 → 200 → 100 → 50 logs/sec, cycling every 8s.
    Starts paused. Responds to storm.control (start / stop / rate).
    30% of events reuse the previous correlationId intentionally.

  processor-fast       tool  /  on-demand  (bidWeight: 1.5, delay: ~5ms)
    Classifies and enriches log entries quickly. Spawned on the first
    invocation; killed by PluginInterceptor after 60s of inactivity.
    Shares ProcessorTool.java with processor-thorough — only plugin.json
    differs (id, bidWeight, processorConfig.delayMs).

  processor-thorough   tool  /  on-demand  (bidWeight: 0.8, delay: ~20ms)
    Slower, more thorough processor. Wins auctions mainly when
    processor-fast is busy (higher effective load reduces its bid).
    Uses the same ProcessorTool.java as processor-fast.

  dashboard            system  /  persistent
    This JavaFX window. Subscribes to storm.metric for live metrics.
    Publishes storm.control to drive Play / Stop / Rate.

EVENTS
────────────────────────────────────────────────────────────────────────────
  capability.invoke  { name: "log.process", service, level, message }
    Published by : log-emitter
    Consumed by  : CapabilityInterceptor → runs auction → routes to winner

  capability.tool.log.process  { service, level, message }
    Published by : CapabilityInterceptor  (triggerEvent rewrite)
    Consumed by  : processor-fast  OR  processor-thorough (auction winner)

  capability.result  { result: { service, level, processor, host, region } }
    Published by : processor-fast / processor-thorough
    Consumed by  : IdempotencyInterceptor → caches → routes to log-emitter
                   (also delivers to collapsed callers simultaneously)

  storm.metric  { level, processor, service, message }
    Published by : processor-fast / processor-thorough  (broadcast)
    Consumed by  : dashboard  (updates metrics, spawns canvas particles)

  storm.control  { action: "start" | "stop" | "rate",  value? }
    Published by : dashboard  (Play/Stop buttons, Rate slider)
    Consumed by  : log-emitter

INTERCEPTOR CHAIN
────────────────────────────────────────────────────────────────────────────
  Every capability.invoke passes through the pipeline in order:

  [IdempotencyInterceptor]  position 0
    Checks the correlationId against the sliding-window cache (5 min TTL).
    Cache hit  → returns cached result instantly, event consumed (true).
    In-flight  → collapses caller onto existing execution, consumed (true).
    New corrId → registers inFlight entry, passes to next interceptor (false).

  [CapabilityInterceptor]  position 1
    Looks up "log.process" in the live provider registry.
    0 providers → checks localCatalog → on-demand → publishes spawn request.
    1 provider  → routes directly to triggerEvent.
    2 providers → starts 500ms auction → routes to effective-score winner.
    Returns true (consumed) — result routed back via pendingRoutes[corrId].

  [PluginInterceptor]  position 2
    Handles system.plugin.spawn: ProcessBuilder launch, stdout → log file.
    Handles system.plugin.usage: updates lastUsed timestamp.
    Runs checkIdlePlugins() every 60s — kills processes past idleTimeout.

RATE SLIDER
────────────────────────────────────────────────────────────────────────────
  Moving the slider publishes storm.control { action: "rate", value: N }.
  LogEmitter's emitLoop uses this value instead of the wave table.
  Set the slider to 0 to resume automatic wave cycling.
  At high rates (>150/sec) you will see the auction balance shift as
  processor-fast's effective score drops and thorough wins more rounds.
""";

    // ── Metrics state ─────────────────────────────────────────────────────────

    final AtomicLong              total      = new AtomicLong();
    final Map<String, AtomicLong> bySeverity = new LinkedHashMap<>();
    final Map<String, AtomicLong> byProcessor= new ConcurrentHashMap<>();
    final List<String>            recentEvts = Collections.synchronizedList(new ArrayList<>());

    volatile long  lastTotal  = 0;
    volatile long  lastRateMs = System.currentTimeMillis();
    volatile long  currentRate = 0;
    volatile OutputStream kernelOut;

    // ── Controls ──────────────────────────────────────────────────────────────

    Label    totalLabel, rateLabel, statusLabel, sliderValueLabel;
    Slider   rateSlider;
    Map<String, ProgressBar> severityBars    = new LinkedHashMap<>();
    Map<String, Label>       severityLabels  = new LinkedHashMap<>();
    Map<String, Label>       processorLabels = new LinkedHashMap<>();
    ListView<String>         eventsList;
    Button   playBtn, stopBtn;
    Canvas   busCanvas;

    // Particles accessed only on FX thread — plain ArrayList is safe
    final List<Particle> particles = new ArrayList<>();

    PluginConfig config;

    // ── Bootstrap ─────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        KernelEvent.initLogging();
        launch(args);
    }

    @Override
    public void start(Stage stage) throws Exception {
        config = PluginConfig.load("plugin.json");
        for (String lvl : List.of("DEBUG", "INFO", "WARN", "ERROR", "FATAL"))
            bySeverity.put(lvl, new AtomicLong());

        Node eventsPanel = buildEventsPanel();
        TabPane tabs = buildTabs(eventsPanel);

        VBox root = new VBox(10, buildHeader(), tabs);
        root.setStyle("-fx-background-color:" + BG_ROOT + "; -fx-padding: 16;");
        VBox.setVgrow(tabs, Priority.ALWAYS);

        Scene scene = new Scene(root, 820, 680);
        applyTabStyle(scene);
        stage.setTitle("🌩 LogStorm — MK8 Load Test");
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> System.exit(0));
        stage.show();

        new AnimationTimer() {
            @Override public void handle(long now) { renderBus(); }
        }.start();

        Thread.ofVirtual().start(this::metricsTicker);
        Thread.ofVirtual().start(this::connectToKernel);
    }

    // ── Tabs ──────────────────────────────────────────────────────────────────

    TabPane buildTabs(Node eventsPanel) {
        TabPane tabs = new TabPane(buildDashboardTab(eventsPanel), buildInfoTab());
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setStyle("-fx-background-color:" + BG_ROOT + ";");
        return tabs;
    }

    Tab buildDashboardTab(Node eventsPanel) {
        Node bottom = buildBottomRow(eventsPanel);
        VBox content = new VBox(10,
                buildMetricsRow(),
                buildSliderRow(),
                buildBusCanvasNode(),
                bottom);
        content.setStyle("-fx-padding: 10 0 0 0; -fx-background-color:" + BG_ROOT + ";");
        VBox.setVgrow(bottom, Priority.ALWAYS);
        return new Tab("📊  Dashboard", content);
    }

    Tab buildInfoTab() {
        TextArea text = new TextArea(INFO_TEXT);
        text.setEditable(false);
        text.setWrapText(false);
        text.setStyle(
                "-fx-control-inner-background:" + BG_ROOT + ";"
                + "-fx-text-fill:" + FG_MAIN + ";"
                + "-fx-font-family: monospace;"
                + "-fx-font-size: 12;"
                + "-fx-border-color: transparent;");
        VBox content = new VBox(text);
        content.setStyle("-fx-padding: 10 0 0 0; -fx-background-color:" + BG_ROOT + ";");
        VBox.setVgrow(text, Priority.ALWAYS);
        return new Tab("ℹ️  Info", content);
    }

    void applyTabStyle(Scene scene) {
        String css =
                ".tab-pane > .tab-header-area { -fx-background-color:" + BG_ROOT + "; }"
                + ".tab { -fx-background-color:" + BG_CARD + "; -fx-padding: 4 12; }"
                + ".tab:selected { -fx-background-color: #1c2128; }"
                + ".tab .tab-label { -fx-text-fill:" + FG_DIM + "; -fx-font-size: 13; }"
                + ".tab:selected .tab-label { -fx-text-fill:" + FG_MAIN + "; }"
                + ".tab-pane > .tab-header-area > .tab-header-background { -fx-background-color:" + BG_ROOT + "; }";
        scene.getStylesheets().add("data:text/css," + css);
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    Node buildHeader() {
        Label title = label("🌩  LogStorm — MK8 Load Test", 17, FG_MAIN, true);
        statusLabel  = label("● Connecting...", 11, FG_DIM, false);

        playBtn = btn("▶  Play", C_PLAY);
        stopBtn = btn("■  Stop", C_STOP);
        stopBtn.setDisable(true);
        playBtn.setOnAction(e -> onPlay());
        stopBtn.setOnAction(e -> onStop());

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox row = new HBox(10, title, statusLabel, gap, playBtn, stopBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    Node buildMetricsRow() {
        totalLabel = label("0", 30, C_BLUE,  true);
        rateLabel  = label("0 / sec", 18, C_GREEN, true);

        HBox row = new HBox(10, card("PROCESSED", totalLabel), card("RATE", rateLabel), buildAuctionCard());
        for (Node n : row.getChildren()) HBox.setHgrow(n, Priority.ALWAYS);
        return row;
    }

    VBox buildAuctionCard() {
        VBox inner = new VBox(6);
        for (String id : List.of("processor-fast", "processor-thorough")) {
            Label val  = label("—", 12, FG_MAIN, false);
            Label name = label(id,  11, FG_DIM,  false);
            processorLabels.put(id, val);
            inner.getChildren().add(new HBox(8, name, val));
        }
        return card("AUCTION WINS", inner);
    }

    Node buildSliderRow() {
        rateSlider = new Slider(10, 300, 20);
        rateSlider.setMajorTickUnit(100);
        rateSlider.setMinorTickCount(4);
        rateSlider.setShowTickMarks(true);
        rateSlider.setShowTickLabels(true);
        rateSlider.setMaxWidth(Double.MAX_VALUE);
        rateSlider.setStyle("-fx-control-inner-background:" + BG_CARD + ";");

        sliderValueLabel = label("20  logs/sec", 12, C_GREEN, true);

        rateSlider.valueProperty().addListener((obs, old, val) -> {
            int v = val.intValue();
            sliderValueLabel.setText(v + "  logs/sec");
            publishRate(v);
        });

        Label title = label("Emission Rate", 11, FG_DIM, false);
        HBox inner = new HBox(12, rateSlider, sliderValueLabel);
        inner.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(rateSlider, Priority.ALWAYS);

        return card("RATE OVERRIDE  (overrides automatic wave)", inner);
    }

    Node buildBusCanvasNode() {
        busCanvas = new Canvas(CANVAS_W, CANVAS_H);
        StackPane pane = new StackPane(busCanvas);
        pane.setStyle("-fx-background-color:" + BG_CARD + "; -fx-background-radius: 8;");
        pane.setMaxWidth(Double.MAX_VALUE);
        return pane;
    }

    Node buildBottomRow(Node eventsPanel) {
        Node severity = buildSeverityPanel();
        HBox row = new HBox(10, severity, eventsPanel);
        HBox.setHgrow(eventsPanel, Priority.ALWAYS);
        return row;
    }

    Node buildSeverityPanel() {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        int r = 0;
        for (String lvl : bySeverity.keySet()) {
            String hex = LEVEL_COLOR.getOrDefault(lvl, FG_DIM);
            Label  lbl = label(lvl, 11, hex, false);
            lbl.setMinWidth(50);

            ProgressBar bar = new ProgressBar(0);
            bar.setMaxWidth(Double.MAX_VALUE);
            bar.setStyle("-fx-accent:" + hex + ";");
            GridPane.setHgrow(bar, Priority.ALWAYS);

            Label pct = label("0%", 11, hex, false);
            pct.setMinWidth(36);

            grid.add(lbl, 0, r);
            grid.add(bar, 1, r);
            grid.add(pct, 2, r);
            severityBars.put(lvl, bar);
            severityLabels.put(lvl, pct);
            r++;
        }
        return card("SEVERITY", grid);
    }

    Node buildEventsPanel() {
        eventsList = new ListView<>();
        eventsList.setStyle("-fx-background-color:" + BG_CARD
                + "; -fx-border-color: #30363d; -fx-border-radius: 6;");
        eventsList.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle(""); return; }
                setText(item);
                String hex = FG_MAIN;
                for (var e : LEVEL_COLOR.entrySet())
                    if (item.contains("[" + e.getKey() + "]")) { hex = e.getValue(); break; }
                setStyle("-fx-text-fill:" + hex
                        + "; -fx-background-color: transparent;"
                        + " -fx-font-family: monospace; -fx-font-size: 11;");
            }
        });
        return card("RECENT EVENTS", eventsList);
    }

    // ── Bus canvas rendering (60fps, FX thread) ───────────────────────────────

    void renderBus() {
        GraphicsContext gc = busCanvas.getGraphicsContext2D();
        gc.setFill(Color.web(BG_CARD));
        gc.fillRoundRect(0, 0, CANVAS_W, CANVAS_H, 10, 10);
        drawBusBar(gc);
        NODES.forEach(n -> drawNode(gc, n));
        renderParticles(gc);
    }

    void drawBusBar(GraphicsContext gc) {
        double bx = 40, bw = CANVAS_W - 80;
        gc.setFill(Color.web(C_BLUE, 0.12));
        gc.fillRoundRect(bx, BUS_Y - 9, bw, 18, 9, 9);
        gc.setStroke(Color.web(C_BLUE, 0.55));
        gc.setLineWidth(1.5);
        gc.strokeRoundRect(bx, BUS_Y - 9, bw, 18, 9, 9);
        gc.setFill(Color.web(C_BLUE, 0.8));
        gc.setFont(Font.font("Monospaced", FontWeight.BOLD, 10));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText("EVENT  BUS", CANVAS_W / 2, BUS_Y + 4);
    }

    void drawNode(GraphicsContext gc, PluginNode n) {
        Color c = Color.web(n.hex());

        double edgeY   = n.y() < BUS_Y ? n.y() + NODE_R : n.y() - NODE_R;
        double busEdgeY = n.y() < BUS_Y ? BUS_Y - 9 : BUS_Y + 9;
        gc.setStroke(c.deriveColor(0, 1, 1, 0.35));
        gc.setLineWidth(1.0);
        gc.setLineDashes(4, 4);
        gc.strokeLine(n.x(), edgeY, n.x(), busEdgeY);
        gc.setLineDashes();

        gc.setFill(c.deriveColor(0, 1, 1, 0.15));
        gc.fillOval(n.x() - NODE_R, n.y() - NODE_R, NODE_R * 2, NODE_R * 2);
        gc.setStroke(c);
        gc.setLineWidth(2.0);
        gc.strokeOval(n.x() - NODE_R, n.y() - NODE_R, NODE_R * 2, NODE_R * 2);

        gc.setFill(c);
        gc.setFont(Font.font("System", 10));
        gc.setTextAlign(TextAlignment.CENTER);
        double labelY = n.y() < BUS_Y ? n.y() - NODE_R - 6 : n.y() + NODE_R + 14;
        gc.fillText(n.label(), n.x(), labelY);
    }

    void renderParticles(GraphicsContext gc) {
        particles.removeIf(Particle::done);
        for (Particle p : particles) {
            if (!p.active()) continue;
            double[] pos = p.pos();
            gc.setFill(p.color.deriveColor(0, 1, 1, 0.35));
            gc.fillOval(pos[0] - 8, pos[1] - 8, 16, 16);
            gc.setFill(p.color);
            gc.fillOval(pos[0] - 5, pos[1] - 5, 10, 10);
        }
    }

    // Three-leg perpendicular path: src → (src.x, busEdge) → (dst.x, busEdge) → dst
    void spawnEvent(String fromId, String toId, Color color) {
        PluginNode src = findNode(fromId);
        PluginNode dst = findNode(toId);
        if (src == null || dst == null) return;

        long   now     = System.currentTimeMillis();
        double srcBusY = src.y() < BUS_Y ? BUS_Y - 8 : BUS_Y + 8;
        double dstBusY = dst.y() < BUS_Y ? BUS_Y - 8 : BUS_Y + 8;

        particles.add(new Particle(src.x(), src.y(),   src.x(), srcBusY,  color,                             now,       260));
        particles.add(new Particle(src.x(), srcBusY,   dst.x(), dstBusY,  color,                             now + 260, 220));
        particles.add(new Particle(dst.x(), dstBusY,   dst.x(), dst.y(),  color.deriveColor(0, 0.8, 1.1, 1), now + 480, 260));
    }

    PluginNode findNode(String id) {
        return NODES.stream().filter(n -> n.id().equals(id)).findFirst().orElse(null);
    }

    // ── Kernel connection ─────────────────────────────────────────────────────

    void connectToKernel() {
        try {
            PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, this::handle);
        } catch (Exception e) {
            Platform.runLater(() -> statusLabel.setText("● Error: " + e.getMessage()));
        }
    }

    void handle(String json, OutputStream out) throws Exception {
        this.kernelOut = out;
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);
        switch (event.type()) {
            case "plugin.ready" -> Platform.runLater(() -> statusLabel.setText("● Connected"));
            case "storm.metric" -> handleMetric(event);
        }
    }

    void handleMetric(KernelEvent event) throws Exception {
        JsonNode p         = KernelEvent.MAPPER.readTree(event.payload());
        String   level     = p.path("level").asText("INFO");
        String   processor = p.path("processor").asText("?");
        String   service   = p.path("service").asText("?");
        String   message   = p.path("message").asText("");

        total.incrementAndGet();
        bySeverity.computeIfAbsent(level, k -> new AtomicLong()).incrementAndGet();
        byProcessor.computeIfAbsent(processor, k -> new AtomicLong()).incrementAndGet();

        String entry = String.format("[%-5s] %-12s  %s", level, service, message);
        synchronized (recentEvts) {
            recentEvts.add(0, entry);
            if (recentEvts.size() > 25) recentEvts.remove(recentEvts.size() - 1);
        }

        Color pColor = Color.web(LEVEL_COLOR.getOrDefault(level, FG_DIM));
        Platform.runLater(() -> {
            spawnEvent("log-emitter", processor,   Color.web(C_GREEN, 0.9));
            spawnEvent(processor,     "dashboard",  pColor);
        });
    }

    // ── Metrics ticker (10fps) ────────────────────────────────────────────────

    void metricsTicker() {
        while (true) {
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            long now     = System.currentTimeMillis();
            long curr    = total.get();
            long elapsed = now - lastRateMs;
            if (elapsed > 0) currentRate = (curr - lastTotal) * 1000 / elapsed;
            lastTotal  = curr;
            lastRateMs = now;
            Platform.runLater(this::refreshUI);
        }
    }

    void refreshUI() {
        long tot = total.get();
        totalLabel.setText(String.format("%,d", tot));
        rateLabel.setText(currentRate + " / sec");

        for (var e : bySeverity.entrySet()) {
            double pct = tot > 0 ? (double) e.getValue().get() / tot : 0;
            severityBars.get(e.getKey()).setProgress(pct);
            severityLabels.get(e.getKey()).setText(String.format("%.0f%%", pct * 100));
        }

        long procTot = byProcessor.values().stream().mapToLong(AtomicLong::get).sum();
        for (var e : processorLabels.entrySet()) {
            long wins = byProcessor.getOrDefault(e.getKey(), new AtomicLong()).get();
            e.getValue().setText(procTot > 0
                    ? String.format("%,d  (%.0f%%)", wins, 100.0 * wins / procTot)
                    : "—");
        }

        List<String> snapshot;
        synchronized (recentEvts) { snapshot = new ArrayList<>(recentEvts); }
        eventsList.getItems().setAll(snapshot);
    }

    // ── Button handlers ───────────────────────────────────────────────────────

    void onPlay() {
        publishControl("start");
        playBtn.setDisable(true);
        stopBtn.setDisable(false);
        statusLabel.setText("● Running");
    }

    void onStop() {
        publishControl("stop");
        playBtn.setDisable(false);
        stopBtn.setDisable(true);
        statusLabel.setText("● Paused");
    }

    void publishControl(String action) {
        if (kernelOut == null) return;
        try {
            String payload = KernelEvent.MAPPER.writeValueAsString(Map.of("action", action));
            PluginBase.publish(KernelEvent.of("storm.control", payload, config.id()), kernelOut);
        } catch (Exception e) {
            System.err.println("[DASHBOARD] Publish error: " + e.getMessage());
        }
    }

    void publishRate(int logsPerSec) {
        if (kernelOut == null) return;
        try {
            String payload = KernelEvent.MAPPER.writeValueAsString(
                    Map.of("action", "rate", "value", logsPerSec));
            PluginBase.publish(KernelEvent.of("storm.control", payload, config.id()), kernelOut);
        } catch (Exception e) {
            System.err.println("[DASHBOARD] Rate publish error: " + e.getMessage());
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    VBox card(String title, Node content) {
        Label hdr = label(title, 10, FG_DIM, false);
        VBox  box = new VBox(8, hdr, content);
        box.setStyle("-fx-background-color:" + BG_CARD
                + "; -fx-padding: 12; -fx-background-radius: 8;");
        return box;
    }

    Label label(String text, int size, String hex, boolean bold) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size:" + size + "; -fx-text-fill:" + hex
                + "; -fx-font-weight:" + (bold ? "bold" : "normal") + ";");
        return l;
    }

    Button btn(String text, String hex) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color:" + hex
                + "; -fx-text-fill: white; -fx-font-weight: bold;"
                + " -fx-background-radius: 6; -fx-padding: 6 14;");
        return b;
    }
}
