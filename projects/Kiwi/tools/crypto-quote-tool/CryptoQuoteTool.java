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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;

/**
 * CryptoQuoteTool — Cryptocurrency price quotation tool.
 *
 * Uses the DIA Data API (https://api.diadata.org) which is free and requires
 * no API key. Supports 3000+ tokens sourced from 100+ exchanges.
 *
 * Input formats accepted:
 *   - Currency pair: "BTC/USD" → extracts base symbol "BTC"
 *   - Coin name:     "Solana"  → maps to symbol "SOL"
 *   - Symbol:        "ETH"     → used directly
 */
public class CryptoQuoteTool {

    static final String PLUGIN_ID = "crypto-quote-tool";
    static final String SOURCE = PLUGIN_ID;

    static final Set<String> CAPABILITIES = Set.of("tool.crypto.quote");

    // DIA API base URL — free, no API key required
    static final String DIA_QUOTATION_URL = "https://api.diadata.org/v1/quotation/";

    static final HttpClient HTTP = HttpClient.newHttpClient();

    // Built-in name-to-symbol mapping for popular cryptocurrencies
    static final Map<String, String> NAME_TO_SYMBOL = Map.ofEntries(
        Map.entry("bitcoin", "BTC"),
        Map.entry("ethereum", "ETH"),
        Map.entry("solana", "SOL"),
        Map.entry("cardano", "ADA"),
        Map.entry("ripple", "XRP"),
        Map.entry("polkadot", "DOT"),
        Map.entry("dogecoin", "DOGE"),
        Map.entry("avalanche", "AVAX"),
        Map.entry("chainlink", "LINK"),
        Map.entry("polygon", "MATIC"),
        Map.entry("litecoin", "LTC"),
        Map.entry("uniswap", "UNI"),
        Map.entry("stellar", "XLM"),
        Map.entry("cosmos", "ATOM"),
        Map.entry("monero", "XMR"),
        Map.entry("tron", "TRX"),
        Map.entry("binance", "BNB"),
        Map.entry("tether", "USDT"),
        Map.entry("usd coin", "USDC"),
        Map.entry("near", "NEAR"),
        Map.entry("aptos", "APT"),
        Map.entry("sui", "SUI"),
        Map.entry("arbitrum", "ARB"),
        Map.entry("optimism", "OP"),
        Map.entry("filecoin", "FIL"),
        Map.entry("hedera", "HBAR"),
        Map.entry("internet computer", "ICP"),
        Map.entry("render", "RNDR"),
        Map.entry("injective", "INJ"),
        Map.entry("celestia", "TIA"),
        Map.entry("sei", "SEI"),
        Map.entry("pepe", "PEPE"),
        Map.entry("shiba inu", "SHIB"),
        Map.entry("matic", "MATIC"),
        Map.entry("bnb", "BNB"),
        Map.entry("toncoin", "TON"),
        Map.entry("kaspa", "KAS"),
        Map.entry("fetch.ai", "FET"),
        Map.entry("immutable", "IMX"),
        Map.entry("mantle", "MNT"),
        Map.entry("stacks", "STX")
    );

    public static void main(String[] args) throws Exception {
        PluginBase.run("plugin.json", KernelEvent.DEFAULT_SOCKET, CryptoQuoteTool::handle);
    }

    /**
     * Main event dispatcher. Routes bid requests and capability invocations.
     */
    static void handle(String json, OutputStream out) throws Exception {
        KernelEvent event = KernelEvent.MAPPER.readValue(json, KernelEvent.class);

        if ("capability.tool.crypto.quote".equals(event.type())) {
            handleQuote(event, out);
        }
    }

    /**
     * Handles a crypto quote request. Calls the DIA API and returns
     * structured price data including 24h change and volume.
     */
    static void handleQuote(KernelEvent event, OutputStream out) throws Exception {
        try {
            JsonNode input = KernelEvent.MAPPER.readTree(event.payload()).path("input");
            String query = input.path("query").asText("").trim();

            if (query.isEmpty()) {
                throw new IllegalArgumentException(
                        "Parameter 'query' is required. Examples: BTC/USD, Solana, ETH");
            }

            // Resolve the user input to a cryptocurrency symbol
            String symbol = resolveSymbol(query);

            // Build and send HTTP request to DIA API
            String url = DIA_QUOTATION_URL + symbol.toUpperCase();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("DIA API returned HTTP " + response.statusCode()
                        + " for symbol '" + symbol + "'. Verify the symbol or name is correct.");
            }

            JsonNode data = KernelEvent.MAPPER.readTree(response.body());

            // Extract quotation fields from the API response
            String sym = data.path("Symbol").asText("N/A");
            String name = data.path("Name").asText("N/A");
            double price = data.path("Price").asDouble(-1);
            double priceYesterday = data.path("PriceYesterday").asDouble(-1);
            double volume = data.path("VolumeYesterdayUSD").asDouble(-1);
            String time = data.path("Time").asText("N/A");
            String blockchain = data.path("Blockchain").asText("N/A");

            if (price < 0) {
                throw new RuntimeException("No price data available for symbol '" + symbol + "'");
            }

            // Calculate 24-hour percentage change
            String change24h = "N/A";
            if (priceYesterday > 0) {
                double pctChange = ((price - priceYesterday) / priceYesterday) * 100.0;
                change24h = String.format("%+.2f%%", pctChange);
            }

            // Human-readable summary
            String result = String.format(
                    "%s (%s) | Blockchain: %s | Price: $%,.2f USD | 24h Change: %s | Volume (24h): $%,.2f | Time: %s | Source: diadata.org",
                    sym, name, blockchain, price, change24h, volume, time
            );

            // Return both the summary string and structured fields
            Map<String, Object> resultData = Map.of(
                    "result", result,
                    "symbol", sym,
                    "name", name,
                    "blockchain", blockchain,
                    "price", price,
                    "priceYesterday", priceYesterday,
                    "change24h", change24h,
                    "volume24h", volume,
                    "time", time,
                    "source", "diadata.org"
            );

            reply(event, resultData, out);

        } catch (Exception e) {
            error(event, e, out);
        }
    }

    /**
     * Resolves a user query to a cryptocurrency symbol.
     *
     * Handles three input formats:
     *   1. Currency pair: "BTC/USD" → extracts base "BTC"
     *   2. Coin name:     "Solana"  → looks up in NAME_TO_SYMBOL → "SOL"
     *   3. Symbol:        "ETH"     → returned as-is (uppercased)
     */
    static String resolveSymbol(String query) {
        // Case 1: Currency pair like "BTC/USD" or "eth/usdt"
        if (query.contains("/")) {
            String base = query.split("/")[0].trim().toUpperCase();
            return base;
        }

        // Case 2: Check if it matches a known coin name (case-insensitive)
        String lowerQuery = query.toLowerCase().trim();
        if (NAME_TO_SYMBOL.containsKey(lowerQuery)) {
            return NAME_TO_SYMBOL.get(lowerQuery);
        }

        // Case 3: Assume the input is already a valid symbol
        return query.toUpperCase().trim();
    }

    /**
     * Sends a successful capability.result reply preserving correlation.
     */
    static void reply(KernelEvent event, Map<?, ?> data, OutputStream out) throws Exception {
        PluginBase.publish(
                KernelEvent.withCorrelation("capability.result",
                        KernelEvent.MAPPER.writeValueAsString(data),
                        SOURCE, event.correlationId(), event.sessionId()),
                out);
    }

    /**
     * Sends a capability.error reply with the exception message.
     */
    static void error(KernelEvent event, Exception e, OutputStream out) throws Exception {
        PluginBase.publish(
                KernelEvent.withCorrelation("capability.error",
                        KernelEvent.MAPPER.writeValueAsString(Map.of(
                                "reason", e.getMessage() != null ? e.getMessage() : e.toString())),
                        SOURCE, event.correlationId(), event.sessionId()),
                out);
    }
}
