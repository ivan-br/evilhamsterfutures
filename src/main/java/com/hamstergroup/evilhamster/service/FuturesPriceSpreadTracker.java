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
 * Discovers USDT-perp futures on supported CEXes and tracks price spreads.
 * Prevents false matches by grouping instruments by:
 *   GROUP KEY = canonicalTicker + "§" + normalizedLongName
 *
 * Where "long name" is a human/readable asset name from the exchange (when available).
 * A small alias map fixes known collisions (e.g. Bitget VELO = Velodrome).
 */
public class FuturesPriceSpreadTracker {

    private final OkHttpClient http = new OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(10))
            .build();

    // refresh universe every 10 minutes
    private volatile Map<String, Map<String, SymbolInfo>> universe = new ConcurrentHashMap<>();
    private volatile long lastRefresh = 0L;
    private static final long SYMBOL_REFRESH_MS = 10 * 60_000L; // 10 minutes

    // ----- known ticker collisions / aliases -----
    // key = exch + ":" + baseTicker (upper)
    private static final Map<String,String> ALIASES = new HashMap<>();
    static {
        // Bitget's VELO is Velodrome
        ALIASES.put("BITGET:VELO", "VELODROME");
        // add more if you encounter them:
        // ALIASES.put("EXCHANGE:TICKER", "Canonical Long Name");
    }

    // -------- data records --------
    public record Quote(String exch, String symbol, BigDecimal price) {}

    // Extra info discovered for a tradable instrument
    public static final class SymbolInfo {
        public final String exchange;      // "Binance"
        public final String nativeSymbol;  // e.g. "BTCUSDT", "BTC_USDT", "BTCUSDT_UMCBL"
        public final String base;          // "BTC"
        public final String quote;         // "USDT"
        public final String longName;      // "Bitcoin" | "Velodrome" | null

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

    /** Scan all discovered instruments; only compare prices inside each (ticker,longName) group. */
    public List<Spread> scanAll() {
        refreshIfStale();

        List<Spread> out = new ArrayList<>();
        for (var groupEntry : universe.entrySet()) {
            String groupKey = groupEntry.getKey();     // e.g., "BTCUSDT§BITCOIN"
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
                    default -> null;
                };
                if (q != null && q.price != null) quotes.add(q);
            }
            if (quotes.size() < 2) continue;

            Quote max = Collections.max(quotes, Comparator.comparing(Quote::price));
            Quote min = Collections.min(quotes, Comparator.comparing(Quote::price));

            // user-visible symbol is the canonical ticker part of the group
            String canonical = groupKey.substring(0, groupKey.indexOf('§'));
            out.add(new Spread(canonical, max, min, quotes));
        }
        return out;
    }

    // --------------------- Discovery & grouping ---------------------
    private void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastRefresh < SYMBOL_REFRESH_MS && !universe.isEmpty()) return;

        Map<String, Map<String, SymbolInfo>> map = new ConcurrentHashMap<>();

        // merge helper
        final java.util.function.BiConsumer<String, List<SymbolInfo>> mergeList = (exch, list) -> {
            for (SymbolInfo si : list) {
                // alias long name if needed
                String alias = aliasLongName(exch, si.base);
                String longNameNorm = normalizeLongName(alias != null ? alias : si.longName, si.base);
                String canonicalTicker = si.base.toUpperCase(Locale.ROOT) + si.quote.toUpperCase(Locale.ROOT);
                String groupKey = canonicalTicker + "§" + longNameNorm; // group by ticker + name

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

        // For BingX/HTX we derive native symbols; use base as long name
        // only if we already saw the canonical ticker elsewhere; this prevents
        // creating lonely groups that never match anything.
        Map<String, Map<String, SymbolInfo>> derived = new HashMap<>(map);

        for (String groupKey : new ArrayList<>(map.keySet())) {
            String canonical = groupKey.substring(0, groupKey.indexOf('§'));
            String base = canonical.replace("USDT", "");
            String longName = groupKey.substring(groupKey.indexOf('§') + 1);

            derived.computeIfAbsent(groupKey, k -> new ConcurrentHashMap<>())
                    .putIfAbsent("BingX", new SymbolInfo("BingX",
                            base + "-USDT", base, "USDT", longName));
            derived.get(groupKey)
                    .putIfAbsent("HTX", new SymbolInfo("HTX",
                            base + "-USDT", base, "USDT", longName));
        }

        universe = derived;
        lastRefresh = now;
    }

    private String aliasLongName(String exch, String baseTicker) {
        return ALIASES.get((exch + ":" + baseTicker).toUpperCase(Locale.ROOT));
    }
    private static String normalizeLongName(String longName, String baseFallback) {
        String s = (longName == null || longName.isBlank()) ? baseFallback : longName;
        s = s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return s;
    }

    // ----- exchange-specific discovery; each returns a list of SymbolInfo -----
    private List<SymbolInfo> discoverBinance() {
        List<SymbolInfo> r = new ArrayList<>();
        JSONObject j = getJson("https://fapi.binance.com/fapi/v1/exchangeInfo");
        if (j == null) return r;
        JSONArray arr = j.optJSONArray("symbols");
        if (arr == null) return r;
        for (int i=0;i<arr.length();i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (!"PERPETUAL".equalsIgnoreCase(o.optString("contractType"))) continue;
            if (!"USDT".equalsIgnoreCase(o.optString("quoteAsset"))) continue;
            String sym = o.optString("symbol", null);
            String base = o.optString("baseAsset", null);
            if (sym == null || base == null) continue;
            r.add(new SymbolInfo("Binance", sym, base, "USDT", base)); // no long name, use base
        }
        return r;
    }

    private List<SymbolInfo> discoverBybit() {
        List<SymbolInfo> r = new ArrayList<>();
        JSONObject j = getJson("https://api.bybit.com/v5/market/instruments-info?category=linear");
        if (j == null) return r;
        JSONObject res = j.optJSONObject("result");
        JSONArray arr = res == null ? null : res.optJSONArray("list");
        if (arr == null) return r;
        for (int i=0;i<arr.length();i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (!"USDT".equalsIgnoreCase(o.optString("quoteCoin"))) continue;
            String sym  = o.optString("symbol", null);
            String base = o.optString("baseCoin", null);
            if (sym == null || base == null) continue;
            r.add(new SymbolInfo("Bybit", sym, base, "USDT", base));
        }
        return r;
    }

    private List<SymbolInfo> discoverKuCoin() {
        List<SymbolInfo> r = new ArrayList<>();
        JSONObject j = getJson("https://api-futures.kucoin.com/api/v1/contracts/active");
        if (j == null) return r;
        JSONArray arr = j.optJSONArray("data");
        if (arr == null) return r;
        for (int i=0;i<arr.length();i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (!"USDT".equalsIgnoreCase(o.optString("quoteCurrency"))) continue;
            if (o.optBoolean("isInverse", false)) continue;
            String sym  = o.optString("symbol", null);          // e.g., XBTUSDTM
            String base = o.optString("baseCurrency", null);    // e.g., XBT
            String disp = o.optString("displayName", null);     // e.g., "Bitcoin Perpetual" (optional)
            if (sym == null || base == null) continue;
            base = base.replace("XBT", "BTC");
            if (sym.toUpperCase(Locale.ROOT).endsWith("USDTM")) {
                // keep native symbol (with trailing M) for KuCoin; price fetch uses same endpoint
            }
            r.add(new SymbolInfo("KuCoin", sym, base, "USDT", disp != null ? disp : base));
        }
        return r;
    }

    private List<SymbolInfo> discoverGate() {
        List<SymbolInfo> r = new ArrayList<>();
        JSONArray arr = getJsonArray("https://api.gateio.ws/api/v4/futures/usdt/contracts");
        if (arr == null) return r;
        for (int i=0;i<arr.length();i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (!"perpetual".equalsIgnoreCase(o.optString("type"))) continue;
            String name = o.optString("name", null);         // BTC_USDT
            String underlying = o.optString("underlying", null); // optional
            if (name == null) continue;
            String base = name.replace("_USDT","").replace("-USDT","").replace("/USDT","");
            String longName = o.optString("instruments_name", null);
            r.add(new SymbolInfo("Gate", name, base, "USDT", (longName != null ? longName : (underlying != null ? underlying : base))));
        }
        return r;
    }

    private List<SymbolInfo> discoverBitget() {
        List<SymbolInfo> r = new ArrayList<>();
        JSONObject j = getJson("https://api.bitget.com/api/mix/v1/market/contracts?productType=umcbl");
        if (j == null) return r;
        JSONArray arr = j.optJSONArray("data");
        if (arr == null) return r;
        for (int i=0;i<arr.length();i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String sym  = o.optString("symbol", null);          // BTCUSDT_UMCBL / VELOUSDT_UMCBL
            String base = o.optString("baseCoin", null);        // BTC / VELO
            String name = o.optString("symbolName", null);      // often human name; for VELO it's "Velodrome" on web
            if (sym == null || base == null) continue;
            // apply alias if present (VELO -> VELODROME)
            String alias = ALIASES.get(("BITGET:" + base).toUpperCase(Locale.ROOT));
            String longName = (alias != null) ? alias : (name != null ? name : base);
            r.add(new SymbolInfo("Bitget", sym, base, "USDT", longName));
        }
        return r;
    }

    private List<SymbolInfo> discoverMexc() {
        List<SymbolInfo> r = new ArrayList<>();
        JSONObject j = getJson("https://contract.mexc.com/api/v1/contract/detail");
        if (j == null) return r;
        JSONArray arr = j.optJSONArray("data");
        if (arr == null) return r;
        for (int i=0;i<arr.length();i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (!"USDT".equalsIgnoreCase(o.optString("quoteCurrency"))) continue;
            if (!"PERPETUAL".equalsIgnoreCase(o.optString("type"))) continue;
            String sym  = o.optString("symbol", null);          // BTC_USDT
            String base = o.optString("baseCurrency", null);    // BTC
            String disp = o.optString("displayName", null);     // optional
            if (sym == null || base == null) continue;
            r.add(new SymbolInfo("MEXC", sym, base, "USDT", disp != null ? disp : base));
        }
        return r;
    }

    // --------------------- Price fetch (defensive) ---------------------
    public record TickerParse(String field, String path) {}
    private Quote fetchBinance(String nativeSymbol) {
        JSONObject j = getJson("https://fapi.binance.com/fapi/v1/ticker/price?symbol=" + enc(nativeSymbol));
        if (j == null) return null;
        String price = j.optString("price", null);
        return price == null ? null : safeQuote("Binance", nativeSymbol, price);
    }
    private Quote fetchBybit(String nativeSymbol) {
        JSONObject j = getJson("https://api.bybit.com/v5/market/tickers?category=linear&symbol=" + enc(nativeSymbol));
        if (j == null) return null;
        JSONObject res = j.optJSONObject("result"); if (res == null) return null;
        JSONArray arr = res.optJSONArray("list");   if (arr == null || arr.isEmpty()) return null;
        JSONObject o = arr.optJSONObject(0);        if (o == null) return null;
        String price = o.optString("lastPrice", null);
        return price == null ? null : safeQuote("Bybit", nativeSymbol, price);
    }
    private Quote fetchKuCoin(String nativeSymbol) {
        JSONObject j = getJson("https://api-futures.kucoin.com/api/v1/contracts/active");
        if (j == null) return null;
        JSONArray arr = j.optJSONArray("data"); if (arr == null) return null;
        for (int i=0;i<arr.length();i++) {
            JSONObject o = arr.optJSONObject(i); if (o == null) continue;
            if (!nativeSymbol.equalsIgnoreCase(o.optString("symbol"))) continue;
            String price = o.optString("lastTradePrice", null);
            return price == null ? null : safeQuote("KuCoin", nativeSymbol, price);
        }
        return null;
    }
    private Quote fetchGate(String nativeSymbol) {
        JSONArray arr = getJsonArray("https://api.gateio.ws/api/v4/futures/usdt/tickers?contract=" + enc(nativeSymbol));
        if (arr == null || arr.isEmpty()) return null;
        JSONObject o = arr.optJSONObject(0); if (o == null) return null;
        String price = o.optString("last", null);
        return price == null ? null : safeQuote("Gate", nativeSymbol, price);
    }
    private Quote fetchBitget(String nativeSymbol) {
        JSONObject j = getJson("https://api.bitget.com/api/mix/v1/market/ticker?symbol=" + enc(nativeSymbol));
        if (j == null) return null;
        JSONObject d = j.optJSONObject("data"); if (d == null) return null;
        String price = d.optString("last", null);
        return price == null ? null : safeQuote("Bitget", nativeSymbol, price);
    }
    private Quote fetchMexc(String nativeSymbol) {
        JSONObject j = getJson("https://contract.mexc.com/api/v1/contract/ticker?symbol=" + enc(nativeSymbol));
        if (j == null) return null;
        JSONArray arr = j.optJSONArray("data"); if (arr == null || arr.isEmpty()) return null;
        JSONObject o = arr.optJSONObject(0); if (o == null) return null;
        String price = o.optString("lastPrice", null);
        return price == null ? null : safeQuote("MEXC", nativeSymbol, price);
    }
    private Quote fetchBingX(String nativeSymbol) {
        JSONObject j = getJson("https://open-api.bingx.com/openApi/swap/v2/quote/price?symbol=" + enc(nativeSymbol));
        if (j == null) return null;
        JSONObject d = j.optJSONObject("data"); if (d == null) return null;
        String price = d.optString("price", null);
        return price == null ? null : safeQuote("BingX", nativeSymbol, price);
    }
    private Quote fetchHTX(String nativeSymbol) {
        JSONObject j = getJson("https://api.hbdm.com/linear-swap-ex/market/detail/merged?contract_code=" + enc(nativeSymbol));
        if (j == null) return null;
        JSONObject tick = j.optJSONObject("tick"); if (tick == null) return null;
        Object close = tick.opt("close");
        String price = close == null ? null : String.valueOf(close);
        return (price == null || "null".equalsIgnoreCase(price)) ? null : safeQuote("HTX", nativeSymbol, price);
    }

    private Quote safeQuote(String exch, String nativeSymbol, String priceStr) {
        try { return new Quote(exch, nativeSymbol, new BigDecimal(priceStr)); }
        catch (Exception e) { return null; }
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
}




