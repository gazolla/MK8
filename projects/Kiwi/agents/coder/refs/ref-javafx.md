# JavaFX Desktop UI (JBang)

JavaFX requires explicit //DEPS. JBang resolves them automatically from Maven Central.
Use for system plugins that open a desktop window (dashboard, monitor, control panel).

---

## Minimal JavaFX window

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//DEPS org.openjfx:javafx-controls:21.0.2
//DEPS org.openjfx:javafx-fxml:21.0.2
//SOURCES ../../../../kernel/KernelEvent.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginBase.java

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import java.io.OutputStream;

public class DashboardFx extends Application {

    static volatile Stage primaryStage;

    public static void main(String[] args) throws Exception {
        // Launch JavaFX in a separate thread; MK8 event loop runs on main thread
        Thread fxThread = new Thread(() -> Application.launch(DashboardFx.class, args));
        fxThread.setDaemon(true);
        fxThread.start();

        // Wait for FX to initialise before starting the kernel connection
        Thread.sleep(1000);
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, DashboardFx::handle);
    }

    @Override
    public void start(Stage stage) {
        primaryStage = stage;

        Label label = new Label("MK8 Dashboard");
        Button btn   = new Button("Refresh");
        VBox root    = new VBox(10, label, btn);
        root.setPadding(new javafx.geometry.Insets(20));

        btn.setOnAction(e -> label.setText("Refreshed at " + java.time.LocalTime.now()));

        stage.setScene(new Scene(root, 400, 300));
        stage.setTitle("MK8 Dashboard");
        stage.show();
    }

    static void handle(String json, OutputStream out) throws Exception {
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);
        // Update UI from event — always dispatch to JavaFX thread:
        Platform.runLater(() -> {
            if (primaryStage != null) {
                // update UI controls here
            }
        });
    }
}
```

---

## Updating UI from a background thread

**Critical**: JavaFX controls must only be modified on the JavaFX Application Thread.
Always wrap UI updates in `Platform.runLater(...)`:

```java
Platform.runLater(() -> {
    myLabel.setText("New value");
    myListView.getItems().add(newItem);
});
```

---

## Adding a TableView for live data

```java
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.*;

ObservableList<String> items = FXCollections.observableArrayList();
TableView<String> table = new TableView<>(items);

// Add a new row from any thread:
Platform.runLater(() -> items.add("new row"));
```

---

## Key deps reference

| Module | Artifact |
|---|---|
| Core controls | `org.openjfx:javafx-controls:21.0.2` |
| FXML support | `org.openjfx:javafx-fxml:21.0.2` |
| Charts | `org.openjfx:javafx-charts:21.0.2` |
| WebView | `org.openjfx:javafx-web:21.0.2` |
| Media | `org.openjfx:javafx-media:21.0.2` |
