package com.hamstergroup.evilhamster.service;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Now includes XT and LBank.
 * XT is fully integrated (discovery + price).
 * LBank is added to mapping & links; price fetch is a TODO (returns null for now).
 */
public class FuturesPriceSpreadTracker {

    private final OkHttpClient http = new OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(10))
            .build();

    // refresh universe every 10 minutes
    private volatile Map<String, Map<String, SymbolInfo>> universe = new ConcurrentHashMap<>();
    private volatile long lastRefresh = 0L;
    private static final long SYMBOL_REFRESH_MS = 10 * 60_000L;

    // Known aliases to avoid ticker-name collisions (example: Bitget VELO = Velodrome)
    private static final Map<String,String> ALIASES = new HashMap<>();
    static {
        ALIASES.put("BITGET:VELO", "VELODROME");
    }

    public record Quote(String exch, String symbol, BigDecimal price) {}

    public static final class SymbolInfo {
        public final String exchange;
        public final String nativeSymbol;
        public final String base;
        public final String quote;
        public final String longName;
        public SymbolInfo(String exchange, String nativeSymbol, String base, String quote, String longName) {
            this.exchange = exchange;
            this.nativeSymbol = nativeSymbol;
            this.base = base;
            this.quote = quote;
            this.longName = longName;
        }
    }

    public record Spread(String symbol, Quote max, Quote min, List<Quote> all) {
        public double spreadPct() {
            if (min.price.compareTo(BigDecimal.ZERO) == 0) return 0d;
            BigDecimal diff = max.price.subtract(min.price);
            return diff.multiply(BigDecimal.valueOf(100))
                    .divide(min.price, 8, RoundingMode.HALF_UP)
                    .doubleValue();
        }
    }

    /** Scan all discovered instruments; prices are compared inside each (ticker,longName) group. */
    public List<Spread> scanAll() {
        refreshIfStale();

        List<Spread> out = new ArrayList<>();
        for (var groupEntry : universe.entrySet()) {
            Map<String, SymbolInfo> perExch = groupEntry.getValue();
            if (perExch.size() < 2) continue;

            List<Quote> quotes = new ArrayList<>();
            for (var e : perExch.entrySet()) {
                String exch = e.getKey();
                SymbolInfo si = e.getValue();
                Quote q = switch (exch) {
                    case "Binance" -> fetchBinance(si.nativeSymbol);
                    case "Bybit"   -> fetchBybit(si.nativeSymbol);
                    case "KuCoin"  -> fetchKuCoin(si.nativeSymbol);
                    case "Gate"    -> fetchGate(si.nativeSymbol);
                    case "Bitget"  -> fetchBitget(si.nativeSymbol);
                    case "MEXC"    -> fetchMexc(si.nativeSymbol);
                    case "BingX"   -> fetchBingX(si.nativeSymbol);
                    case "HTX"     -> fetchHTX(si.nativeSymbol);
                    case "XT"      -> fetchXT(si.nativeSymbol);     // ✅ new
                    case "LBank"   -> fetchLBank(si.nativeSymbol);  // 🚧 returns null for now
                    default -> null;
                };
                if (q != null && q.price != null) quotes.add(q);
            }
            if (quotes.size() < 2) continue;

            Quote max = Collections.max(quotes, Comparator.comparing(Quote::price));
            Quote min = Collections.min(quotes, Comparator.comparing(Quote::price));

            String canonical = groupEntry.getKey().substring(0, groupEntry.getKey().indexOf('§'));
            out.add(new Spread(canonical, max, min, quotes));
        }
        return out;
    }

    // --------------------- Discovery & grouping ---------------------
    private void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastRefresh < SYMBOL_REFRESH_MS && !universe.isEmpty()) return;

        Map<String, Map<String, SymbolInfo>> map = new ConcurrentHashMap<>();

        final java.util.function.BiConsumer<String, List<SymbolInfo>> mergeList = (exch, list) -> {
            for (SymbolInfo si : list) {
                String alias = aliasLongName(exch, si.base);
                String longNameNorm = normalizeLongName(alias != null ? alias : si.longName, si.base);
                String canonicalTicker = si.base.toUpperCase(Locale.ROOT) + si.quote.toUpperCase(Locale.ROOT);
                String groupKey = canonicalTicker + "§" + longNameNorm;
                map.computeIfAbsent(groupKey, k -> new ConcurrentHashMap<>())
                        .put(exch, new SymbolInfo(exch, si.nativeSymbol, si.base, si.quote, longNameNorm));
            }
        };

        mergeList.accept("Binance", discoverBinance());
        mergeList.accept("Bybit",   discoverBybit());
        mergeList.accept("KuCoin",  discoverKuCoin());
        mergeList.accept("Gate",    discoverGate());
        mergeList.accept("Bitget",  discoverBitget());
        mergeList.accept("MEXC",    discoverMexc());
        mergeList.accept("XT",      discoverXT());     // ✅ new

        // For BingX/HTX/LBank: derive native symbols from canonical; price fetchers handle (or return null)
        Map<String, Map<String, SymbolInfo>> derived = new HashMap<>(map);
        for (String groupKey : new ArrayList<>(map.keySet())) {
            String canonical = groupKey.substring(0, groupKey.indexOf('§'));
            String base = canonical.replace("USDT", "");
            String longName = groupKey.substring(groupKey.indexOf('§') + 1);

            derived.computeIfAbsent(groupKey, k -> new ConcurrentHashMap<>())
                    .putIfAbsent("BingX", new SymbolInfo("BingX", base + "-USDT", base, "USDT", longName));
            derived.get(groupKey)
                    .putIfAbsent("HTX",   new SymbolInfo("HTX",   base + "-USDT", base, "USDT", longName));
            derived.get(groupKey)
                    .putIfAbsent("LBank", new SymbolInfo("LBank", base.toLowerCase(Locale.ROOT) + "usdt", base, "USDT", longName)); // 🚧 derived
        }

        universe = derived;
        lastRefresh = now;
    }

    private String aliasLongName(String exch, String baseTicker) {
        return ALIASES.get((exch + ":" + baseTicker).toUpperCase(Locale.ROOT));
    }
    private static String normalizeLongName(String longName, String baseFallback) {
        String s = (longName == null || longName.isBlank()) ? baseFallback : longName;
        return s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    // ----- discovery per exchange (unchanged ones omitted for brevity) -----
    private List<SymbolInfo> discoverBinance() { /* ...same as before... */ return discoverFromBinanceImpl(); }
    private List<SymbolInfo> discoverBybit()   { /* ...same as before... */ return discoverFromBybitImpl(); }
    private List<SymbolInfo> discoverKuCoin()  { /* ...same as before... */ return discoverFromKuCoinImpl(); }
    private List<SymbolInfo> discoverGate()    { /* ...same as before... */ return discoverFromGateImpl(); }
    private List<SymbolInfo> discoverBitget()  { /* ...same as before... */ return discoverFromBitgetImpl(); }
    private List<SymbolInfo> discoverMexc()    { /* ...same as before... */ return discoverFromMexcImpl(); }

    /** XT discovery (USDT-M + PERPETUAL) via public contracts endpoint. */
    private List<SymbolInfo> discoverXT() {
        List<SymbolInfo> r = new ArrayList<>();
        JSONArray arr = getJsonArray("https://fapi.xt.com/future/market/v1/public/cg/contracts"); // returns list with last_price, base_currency, target_currency, product_type, symbol, ...
        if (arr == null) return r;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (!"PERPETUAL".equalsIgnoreCase(o.optString("product_type"))) continue;
            String quote = o.optString("target_currency", "");
            if (!"USDT".equalsIgnoreCase(quote)) continue; // only USDT-M
            String base = o.optString("base_currency", null);
            String sym  = o.optString("symbol", null); // e.g., "btc_usdt"
            if (base == null || sym == null) continue;
            r.add(new SymbolInfo("XT", sym.toLowerCase(Locale.ROOT), base.toUpperCase(Locale.ROOT), "USDT", base));
        }
        return r;
    }

    // --------------------- price fetchers (defensive) ---------------------
    private Quote fetchBinance(String nativeSymbol) { /* ...same as before... */ return fetchFromBinanceImpl(nativeSymbol); }
    private Quote fetchBybit(String nativeSymbol)   { /* ...same as before... */ return fetchFromBybitImpl(nativeSymbol); }
    private Quote fetchKuCoin(String nativeSymbol)  { /* ...same as before... */ return fetchFromKuCoinImpl(nativeSymbol); }
    private Quote fetchGate(String nativeSymbol)    { /* ...same as before... */ return fetchFromGateImpl(nativeSymbol); }
    private Quote fetchBitget(String nativeSymbol)  { /* ...same as before... */ return fetchFromBitgetImpl(nativeSymbol); }
    private Quote fetchMexc(String nativeSymbol)    { /* ...same as before... */ return fetchFromMexcImpl(nativeSymbol); }
    private Quote fetchBingX(String nativeSymbol)   { /* ...same as before... */ return fetchFromBingXImpl(nativeSymbol); }
    private Quote fetchHTX(String nativeSymbol)     { /* ...same as before... */ return fetchFromHTXImpl(nativeSymbol); }

    /** XT price: reuse the same contracts endpoint and read `last_price` for the symbol. */
    private Quote fetchXT(String nativeSymbol) {
        JSONArray arr = getJsonArray("https://fapi.xt.com/future/market/v1/public/cg/contracts");
        if (arr == null) return null;
        String needle = nativeSymbol.toLowerCase(Locale.ROOT);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (!needle.equalsIgnoreCase(o.optString("symbol"))) continue;
            String p = o.optString("last_price", null);
            if (p == null) return null;
            try { return new Quote("XT", nativeSymbol, new BigDecimal(p)); } catch (Exception ignore) { return null; }
        }
        return null;
    }

    /** LBank price (TODO): public futures REST ticker endpoint isn’t documented; returning null keeps it harmless. */
    private Quote fetchLBank(String nativeSymbol) {
        return null; // TODO: swap in a confirmed LBank futures ticker endpoint when available
    }

    // --------------------- Contract page URLs ---------------------
    public String exchangeUrl(String exch, String canonical) {
        String base = canonical;
        return switch (exch) {
            case "Binance" -> "https://www.binance.com/en/futures/" + base;
            case "Bybit"   -> "https://www.bybit.com/trade/usdt/" + base;
            case "KuCoin"  -> "https://futures.kucoin.com/trade/" + base;
            case "Gate"    -> "https://www.gate.io/futures_trade/USDT/" + base;
            case "Bitget"  -> "https://www.bitget.com/futures/usdt/" + base;
            case "MEXC"    -> "https://futures.mexc.com/exchange/" + base;
            case "BingX"   -> "https://bingx.com/en-us/futures/" + base.toLowerCase();
            case "HTX"     -> "https://futures.htx.com/en-us/usdt_swap/exchange/" + base.toLowerCase();
            case "XT"      -> "https://www.xt.com/en/futures/trade/" + base.toLowerCase().replace("usdt", "_usdt"); // e.g., btc_usdt
            case "LBank"   -> "https://www.lbank.com/futures/" + base.toLowerCase(); // e.g., btcusdt
            default -> "#";
        };
    }

    // --------------------- HTTP helpers ---------------------
    private JSONObject getJson(String url) {
        try (Response r = http.newCall(new Request.Builder().url(url).get().build()).execute()) {
            if (!r.isSuccessful() || r.body() == null) return null;
            String body = r.body().string();
            if (body == null || body.isEmpty()) return null;
            try { return new JSONObject(body); } catch (Exception e) { return null; }
        } catch (IOException e) { return null; }
    }
    private JSONArray getJsonArray(String url) {
        try (Response r = http.newCall(new Request.Builder().url(url).get().build()).execute()) {
            if (!r.isSuccessful() || r.body() == null) return null;
            String body = r.body().string();
            if (body == null || body.isEmpty()) return null;
            try { return new JSONArray(body); } catch (Exception e) { return null; }
        } catch (IOException e) { return null; }
    }
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    // -------------- original exchange-specific impls (unchanged) --------------
    // (keep your previously working implementations here)
    private List<SymbolInfo> discoverFromBinanceImpl(){ /* ...existing code... */ return new ArrayList<>(); }
    private List<SymbolInfo> discoverFromBybitImpl(){ /* ...existing code... */ return new ArrayList<>(); }
    private List<SymbolInfo> discoverFromKuCoinImpl(){ /* ...existing code... */ return new ArrayList<>(); }
    private List<SymbolInfo> discoverFromGateImpl(){ /* ...existing code... */ return new ArrayList<>(); }
    private List<SymbolInfo> discoverFromBitgetImpl(){ /* ...existing code... */ return new ArrayList<>(); }
    private List<SymbolInfo> discoverFromMexcImpl(){ /* ...existing code... */ return new ArrayList<>(); }

    private Quote fetchFromBinanceImpl(String s){ /* ...existing code... */ return null; }
    private Quote fetchFromBybitImpl(String s){ /* ...existing code... */ return null; }
    private Quote fetchFromKuCoinImpl(String s){ /* ...existing code... */ return null; }
    private Quote fetchFromGateImpl(String s){ /* ...existing code... */ return null; }
    private Quote fetchFromBitgetImpl(String s){ /* ...existing code... */ return null; }
    private Quote fetchFromMexcImpl(String s){ /* ...existing code... */ return null; }
    private Quote fetchFromBingXImpl(String s){ /* ...existing code... */ return null; }
    private Quote fetchFromHTXImpl(String s){ /* ...existing code... */ return null; }
}




