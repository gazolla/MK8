///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../../../kernel/KernelEvent.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginConfig.java
//SOURCES ../../../../kernel/interceptors/plugin/PluginBase.java

import com.fasterxml.jackson.databind.JsonNode;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class WeatherTool {

    static final Set<String> CAPABILITIES = Set.of("tool.weather.get");
    static final HttpClient HTTP = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, WeatherTool::handle);
    }

    static void handle(String json, OutputStream out) throws Exception {
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);

        if ("capability.tool.weather.get".equals(event.type())) {
            handleWeatherGet(event, out);
        }
    }

    static void handleWeatherGet(KernelEvent event, OutputStream out) throws Exception {
        try {
            JsonNode input = KernelEvent.MAPPER.readTree(event.payload()).path("input");
            String city = input.path("city").asText("");
            if (city.isBlank()) {
                error(event, "Parameter 'city' is required", out);
                return;
            }

            String date = input.has("date") ? input.path("date").asText(null) : null;
            String startDate = input.has("startDate") ? input.path("startDate").asText(null) : null;
            String endDate = input.has("endDate") ? input.path("endDate").asText(null) : null;

            // Determine effective start and end dates
            String effectiveStart;
            String effectiveEnd;

            if (date != null && !date.isBlank()) {
                effectiveStart = date;
                effectiveEnd = date;
            } else if (startDate != null && !startDate.isBlank()) {
                effectiveStart = startDate;
                effectiveEnd = (endDate != null && !endDate.isBlank()) ? endDate : startDate;
            } else {
                String today = LocalDate.now().toString();
                effectiveStart = today;
                effectiveEnd = today;
            }

            // Step 1: Geocode city to get lat/lon
            String geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name="
                    + URLEncoder.encode(city, StandardCharsets.UTF_8)
                    + "&count=1&language=en&format=json";

            HttpRequest geoReq = HttpRequest.newBuilder()
                    .uri(URI.create(geoUrl))
                    .GET()
                    .build();

            HttpResponse<String> geoResp = HTTP.send(geoReq, HttpResponse.BodyHandlers.ofString());
            if (geoResp.statusCode() != 200) {
                error(event, "Geocoding API returned HTTP " + geoResp.statusCode(), out);
                return;
            }

            JsonNode geoData = KernelEvent.MAPPER.readTree(geoResp.body());
            JsonNode results = geoData.path("results");
            if (!results.isArray() || results.isEmpty()) {
                error(event, "City not found: " + city, out);
                return;
            }

            JsonNode location = results.get(0);
            double lat = location.path("latitude").asDouble();
            double lon = location.path("longitude").asDouble();
            String resolvedCity = location.path("name").asText(city);
            String country = location.path("country").asText("");

            // Step 2: Fetch weather forecast from Open-Meteo
            String forecastUrl = "https://api.open-meteo.com/v1/forecast"
                    + "?latitude=" + lat
                    + "&longitude=" + lon
                    + "&daily=temperature_2m_max,temperature_2m_min,weather_code"
                    + "&start_date=" + effectiveStart
                    + "&end_date=" + effectiveEnd
                    + "&timezone=auto";

            HttpRequest forecastReq = HttpRequest.newBuilder()
                    .uri(URI.create(forecastUrl))
                    .GET()
                    .build();

            HttpResponse<String> forecastResp = HTTP.send(forecastReq, HttpResponse.BodyHandlers.ofString());
            if (forecastResp.statusCode() != 200) {
                error(event, "Forecast API returned HTTP " + forecastResp.statusCode(), out);
                return;
            }

            JsonNode forecastData = KernelEvent.MAPPER.readTree(forecastResp.body());
            JsonNode daily = forecastData.path("daily");

            JsonNode dates = daily.path("time");
            JsonNode maxTemps = daily.path("temperature_2m_max");
            JsonNode minTemps = daily.path("temperature_2m_min");
            JsonNode weatherCodes = daily.path("weather_code");

            List<Map<String, Object>> dailyList = new ArrayList<>();
            if (dates.isArray()) {
                for (int i = 0; i < dates.size(); i++) {
                    Map<String, Object> day = new HashMap<>();
                    day.put("date", dates.get(i).asText());
                    day.put("maxTemp", maxTemps.isArray() && i < maxTemps.size()
                            ? maxTemps.get(i).asDouble() : null);
                    day.put("minTemp", minTemps.isArray() && i < minTemps.size()
                            ? minTemps.get(i).asDouble() : null);
                    int code = weatherCodes.isArray() && i < weatherCodes.size()
                            ? weatherCodes.get(i).asInt(-1) : -1;
                    day.put("weatherCode", code);
                    day.put("weatherDescription", describeWeatherCode(code));
                    dailyList.add(day);
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("city", resolvedCity);
            result.put("country", country);
            result.put("latitude", lat);
            result.put("longitude", lon);
            result.put("startDate", effectiveStart);
            result.put("endDate", effectiveEnd);
            result.put("daily", dailyList);

            reply(event, Map.of("result", result), out);

        } catch (Exception e) {
            error(event, e, out);
        }
    }

    static String describeWeatherCode(int code) {
        return switch (code) {
            case 0 -> "Clear sky";
            case 1 -> "Mainly clear";
            case 2 -> "Partly cloudy";
            case 3 -> "Overcast";
            case 45, 48 -> "Fog";
            case 51 -> "Light drizzle";
            case 53 -> "Moderate drizzle";
            case 55 -> "Dense drizzle";
            case 56, 57 -> "Freezing drizzle";
            case 61 -> "Slight rain";
            case 63 -> "Moderate rain";
            case 65 -> "Heavy rain";
            case 66, 67 -> "Freezing rain";
            case 71 -> "Slight snow fall";
            case 73 -> "Moderate snow fall";
            case 75 -> "Heavy snow fall";
            case 77 -> "Snow grains";
            case 80 -> "Slight rain showers";
            case 81 -> "Moderate rain showers";
            case 82 -> "Violent rain showers";
            case 85 -> "Slight snow showers";
            case 86 -> "Heavy snow showers";
            case 95 -> "Thunderstorm";
            case 96, 99 -> "Thunderstorm with hail";
            default -> "Unknown";
        };
    }


    static void reply(KernelEvent event, Map<?, ?> data, OutputStream out) throws Exception {
        PluginBase.publish(
                KernelEvent.withCorrelation("capability.result",
                        KernelEvent.MAPPER.writeValueAsString(data),
                        "tool-weather",
                        event.correlationId(), event.sessionId()),
                out);
    }

    static void error(KernelEvent event, Exception e, OutputStream out) throws Exception {
        error(event, e.getMessage(), out);
    }

    static void error(KernelEvent event, String message, OutputStream out) throws Exception {
        PluginBase.publish(
                KernelEvent.withCorrelation("capability.error",
                        KernelEvent.MAPPER.writeValueAsString(
                                Map.of("error", message != null ? message : "unknown")),
                        "tool-weather",
                        event.correlationId(), event.sessionId()),
                out);
    }
}