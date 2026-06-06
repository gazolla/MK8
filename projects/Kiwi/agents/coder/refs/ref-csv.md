# CSV and Excel — Parsing and Writing (JBang)

CSV reading without deps (built-in). Excel requires Apache POI.

---

## CSV — read (no //DEPS, built-in)

```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../../kernel/KernelEvent.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginBase.java

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Reads a CSV file into a List of Maps (header → value).
 * Handles quoted fields (RFC 4180).
 */
static List<Map<String, String>> readCsv(Path file) throws Exception {
    List<Map<String, String>> rows = new ArrayList<>();
    try (BufferedReader reader = Files.newBufferedReader(file)) {
        String headerLine = reader.readLine();
        if (headerLine == null) return rows;
        String[] headers = parseCsvLine(headerLine);

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            String[] values = parseCsvLine(line);
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < headers.length; i++)
                row.put(headers[i], i < values.length ? values[i] : "");
            rows.add(row);
        }
    }
    return rows;
}

// RFC 4180 CSV line parser — handles quoted fields and embedded commas/newlines
static String[] parseCsvLine(String line) {
    List<String> fields = new ArrayList<>();
    StringBuilder sb = new StringBuilder();
    boolean inQuotes = false;
    for (int i = 0; i < line.length(); i++) {
        char c = line.charAt(i);
        if (inQuotes) {
            if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                sb.append('"'); i++; // escaped quote
            } else if (c == '"') {
                inQuotes = false;
            } else {
                sb.append(c);
            }
        } else {
            if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(sb.toString()); sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
    }
    fields.add(sb.toString());
    return fields.toArray(new String[0]);
}
```

---

## CSV — write (no //DEPS)

```java
static void writeCsv(Path file, List<String> headers, List<Map<String, String>> rows) throws Exception {
    try (BufferedWriter writer = Files.newBufferedWriter(file)) {
        writer.write(String.join(",", headers.stream().map(CsvTool::quoteField).toList()));
        writer.newLine();
        for (Map<String, String> row : rows) {
            writer.write(String.join(",",
                    headers.stream().map(h -> quoteField(row.getOrDefault(h, ""))).toList()));
            writer.newLine();
        }
    }
}

static String quoteField(String value) {
    if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
    return value;
}
```

---

## CSV — OpenCSV (handles edge cases, streaming large files)

```java
//DEPS com.opencsv:opencsv:5.9

import com.opencsv.*;
import com.opencsv.bean.*;
import java.io.*;
import java.nio.file.*;

// Read with header mapping
try (CSVReader reader = new CSVReaderBuilder(Files.newBufferedReader(Path.of("data.csv")))
        .withSkipLines(0).build()) {
    List<String[]> rows = reader.readAll();
    String[] headers = rows.get(0);
    for (int i = 1; i < rows.size(); i++) {
        String[] row = rows.get(i);
        // process row[0], row[1], ...
    }
}

// Write
try (CSVWriter writer = new CSVWriter(Files.newBufferedWriter(Path.of("out.csv")))) {
    writer.writeNext(new String[]{"name", "age", "email"});
    writer.writeNext(new String[]{"Alice", "30", "alice@example.com"});
}
```

---

## Excel — Apache POI (.xlsx read and write)

```java
//DEPS org.apache.poi:poi-ooxml:5.3.0

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.*;
import java.nio.file.*;

// Read .xlsx
static List<Map<String, String>> readExcel(Path file) throws Exception {
    List<Map<String, String>> rows = new ArrayList<>();
    try (Workbook wb = new XSSFWorkbook(Files.newInputStream(file))) {
        Sheet sheet = wb.getSheetAt(0);
        Row headerRow = sheet.getRow(0);
        if (headerRow == null) return rows;

        List<String> headers = new ArrayList<>();
        for (Cell cell : headerRow) headers.add(getCellValue(cell));

        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            Map<String, String> map = new LinkedHashMap<>();
            for (int c = 0; c < headers.size(); c++) {
                Cell cell = row.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                map.put(headers.get(c), getCellValue(cell));
            }
            rows.add(map);
        }
    }
    return rows;
}

static String getCellValue(Cell cell) {
    return switch (cell.getCellType()) {
        case STRING  -> cell.getStringCellValue();
        case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                ? cell.getLocalDateTimeCellValue().toString()
                : String.valueOf((long) cell.getNumericCellValue());
        case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
        case FORMULA -> cell.getCellFormula();
        default      -> "";
    };
}

// Write .xlsx
static void writeExcel(Path file, List<String> headers, List<Map<String, String>> rows) throws Exception {
    try (Workbook wb = new XSSFWorkbook()) {
        Sheet sheet = wb.createSheet("Sheet1");

        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.size(); i++)
            headerRow.createCell(i).setCellValue(headers.get(i));

        for (int r = 0; r < rows.size(); r++) {
            Row row = sheet.createRow(r + 1);
            for (int c = 0; c < headers.size(); c++)
                row.createCell(c).setCellValue(rows.get(r).getOrDefault(headers.get(c), ""));
        }

        try (OutputStream os = Files.newOutputStream(file)) { wb.write(os); }
    }
}
```

---

## CSV → JSON (common pipeline pattern)

```java
List<Map<String, String>> csvRows = readCsv(Path.of("input.csv"));
String json = KernelEvent.MAPPER.writeValueAsString(csvRows);
// json is now a JSON array of objects
```
