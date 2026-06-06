# Database — SQLite, H2 Embedded, JDBC (JBang)

Embedded databases need no server. SQLite for persistent files, H2 for in-memory or dev.

---

## SQLite — persistent file database

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//DEPS org.xerial:sqlite-jdbc:3.46.1.3
//SOURCES ../../../../kernel/KernelEvent.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginBase.java

import java.sql.*;
import java.util.*;

// Database file sits next to the .java file; use absolute path for reliability
static final String DB_URL = "jdbc:sqlite:" +
        java.nio.file.Path.of("data.db").toAbsolutePath();

// Create table on startup (idempotent)
static void initDb() throws Exception {
    try (Connection conn = DriverManager.getConnection(DB_URL);
         Statement stmt = conn.createStatement()) {
        stmt.execute("""
                CREATE TABLE IF NOT EXISTS items (
                    id    INTEGER PRIMARY KEY AUTOINCREMENT,
                    key   TEXT    NOT NULL UNIQUE,
                    value TEXT    NOT NULL,
                    ts    INTEGER NOT NULL DEFAULT (strftime('%s','now'))
                )
                """);
    }
}
```

---

## CRUD patterns — PreparedStatement (always use — never string-concatenate SQL)

```java
// INSERT or REPLACE
static void upsert(String key, String value) throws Exception {
    try (Connection conn = DriverManager.getConnection(DB_URL);
         PreparedStatement ps = conn.prepareStatement(
                 "INSERT OR REPLACE INTO items (key, value) VALUES (?, ?)")) {
        ps.setString(1, key);
        ps.setString(2, value);
        ps.executeUpdate();
    }
}

// SELECT single row
static Optional<String> get(String key) throws Exception {
    try (Connection conn = DriverManager.getConnection(DB_URL);
         PreparedStatement ps = conn.prepareStatement(
                 "SELECT value FROM items WHERE key = ?")) {
        ps.setString(1, key);
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(rs.getString("value")) : Optional.empty();
        }
    }
}

// SELECT multiple rows → List of Maps
static List<Map<String, Object>> list(int limit) throws Exception {
    try (Connection conn = DriverManager.getConnection(DB_URL);
         PreparedStatement ps = conn.prepareStatement(
                 "SELECT key, value, ts FROM items ORDER BY ts DESC LIMIT ?")) {
        ps.setInt(1, limit);
        try (ResultSet rs = ps.executeQuery()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(Map.of(
                        "key",   rs.getString("key"),
                        "value", rs.getString("value"),
                        "ts",    rs.getLong("ts")));
            }
            return rows;
        }
    }
}

// DELETE
static void delete(String key) throws Exception {
    try (Connection conn = DriverManager.getConnection(DB_URL);
         PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM items WHERE key = ?")) {
        ps.setString(1, key);
        ps.executeUpdate();
    }
}
```

---

## Transaction — atomic batch operations

```java
static void batchInsert(List<Map.Entry<String, String>> entries) throws Exception {
    try (Connection conn = DriverManager.getConnection(DB_URL)) {
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO items (key, value) VALUES (?, ?)")) {
            for (var entry : entries) {
                ps.setString(1, entry.getKey());
                ps.setString(2, entry.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (Exception e) {
            conn.rollback();
            throw e;
        }
    }
}
```

---

## H2 — in-memory database (no file, data lost on restart)

```java
//DEPS com.h2database:h2:2.2.224

static final String H2_URL = "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1";
// For persistent H2 file: "jdbc:h2:./data/mydb"
// For H2 web console:     "jdbc:h2:./data/mydb;AUTO_SERVER=TRUE"
```

H2 is a drop-in replacement for SQLite in tests. Replace `DB_URL` with `H2_URL` and keep the same JDBC code.

---

## Connection pool — HikariCP (for high-concurrency tools)

```java
//DEPS com.zaxxer:HikariCP:5.1.0

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

static final HikariDataSource POOL;
static {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(DB_URL);
    config.setMaximumPoolSize(5);
    config.setConnectionTimeout(3000);
    POOL = new HikariDataSource(config);
}

// Usage: replace DriverManager.getConnection(DB_URL) with POOL.getConnection()
static void upsertPooled(String key, String value) throws Exception {
    try (Connection conn = POOL.getConnection();
         PreparedStatement ps = conn.prepareStatement(
                 "INSERT OR REPLACE INTO items (key, value) VALUES (?, ?)")) {
        ps.setString(1, key);
        ps.setString(2, value);
        ps.executeUpdate();
    }
}
```

---

## ResultSet → JSON (serialize query results)

```java
static String resultSetToJson(ResultSet rs) throws Exception {
    ResultSetMetaData meta = rs.getMetaData();
    int cols = meta.getColumnCount();
    var rows = new ArrayList<Map<String, Object>>();
    while (rs.next()) {
        var row = new LinkedHashMap<String, Object>();
        for (int i = 1; i <= cols; i++)
            row.put(meta.getColumnLabel(i), rs.getObject(i));
        rows.add(row);
    }
    return KernelEvent.MAPPER.writeValueAsString(rows);
}
```
