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
 * Discovers ALL USDT-perp futures and tracks spreads across:
 * Binance, Bybit, KuCoin, Gate, Bitget, MEXC, BingX, HTX.
 *
 * All fetchers are defensive: they use opt* accessors and null-checks.
 */
public class FuturesPriceSpreadTracker {

    private final OkHttpClient http = new OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(10))
            .build();

    // cache of discovered symbols
    private volatile Map<String, Map<String, String>> symbolMap = new ConcurrentHashMap<>();
    private volatile long lastRefresh = 0L;
    private static final long SYMBOL_REFRESH_MS = 10 * 60_000L; // 10 minutes

    public record Quote(String exch, String symbol, BigDecimal price) {}
    public record Spread(String symbol, Quote max, Quote min, List<Quote> all) {
        public double spreadPct() {
            if (min.price.compareTo(BigDecimal.ZERO) == 0) return 0d;
            BigDecimal diff = max.price.subtract(min.price);
            return diff.multiply(BigDecimal.valueOf(100))
                    .divide(min.price, 8, RoundingMode.HALF_UP)
                    .doubleValue();
        }
    }

    /** Ensure symbols discovered, then scan all tradables that exist on at least 2 exchanges. */
    public List<Spread> scanAll() {
        refreshSymbolsIfStale();

        List<Spread> out = new ArrayList<>();
        for (var entry : symbolMap.entrySet()) {
            String canonical = entry.getKey();
            Map<String, String> nativePerExchange = entry.getValue();
            if (nativePerExchange.size() < 2) continue;

            List<Quote> quotes = new ArrayList<>();
            for (var e : nativePerExchange.entrySet()) {
                String exch = e.getKey();
                String nativeSymbol = e.getValue();
                Quote q = switch (exch) {
                    case "Binance" -> fetchBinance(nativeSymbol);
                    case "Bybit"   -> fetchBybit(nativeSymbol);
                    case "KuCoin"  -> fetchKuCoin(nativeSymbol);
                    case "Gate"    -> fetchGate(nativeSymbol);
                    case "Bitget"  -> fetchBitget(nativeSymbol); // already *_UMCBL from discovery
                    case "MEXC"    -> fetchMexc(nativeSymbol);
                    case "BingX"   -> fetchBingX(nativeSymbol);
                    case "HTX"     -> fetchHTX(nativeSymbol);
                    default -> null;
                };
                if (q != null && q.price != null) quotes.add(q);
            }
            if (quotes.size() < 2) continue;

            Quote max = Collections.max(quotes, Comparator.comparing(Quote::price));
            Quote min = Collections.min(quotes, Comparator.comparing(Quote::price));
            out.add(new Spread(canonical, max, min, quotes));
        }
        return out;
    }

    // --------------------- Discovery ---------------------
    private void refreshSymbolsIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastRefresh < SYMBOL_REFRESH_MS && !symbolMap.isEmpty()) return;

        Map<String, Map<String, String>> map = new ConcurrentHashMap<>();

        final java.util.function.BiConsumer<String, Map<String,String>> merge = (exch, m) -> {
            for (var e : m.entrySet()) {
                String can = e.getKey();
                map.computeIfAbsent(can, k -> new ConcurrentHashMap<>()).put(exch, e.getValue());
            }
        };

        merge.accept("Binance", discoverBinance());
        merge.accept("Bybit",   discoverBybit());
        merge.accept("KuCoin",  discoverKuCoin());
        merge.accept("Gate",    discoverGate());
        merge.accept("Bitget",  discoverBitget());
        merge.accept("MEXC",    discoverMexc());

        // Derive BingX/HTX native symbols from canonical (most public endpoints work)
        Map<String, Map<String, String>> derived = new HashMap<>();
        for (String can : map.keySet()) {
            Map<String,String> inner = new HashMap<>(map.get(can));
            inner.put("BingX", can.replace("USDT","-USDT"));
            inner.put("HTX",   can.replace("USDT","-USDT"));
            derived.put(can, inner);
        }
        symbolMap = derived;
        lastRefresh = now;
    }

    private Map<String,String> discoverBinance() {
        Map<String,String> r = new HashMap<>();
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
            if (sym == null) continue;
            String can = canonical(sym);
            r.put(can, sym);
        }
        return r;
    }

    private Map<String,String> discoverBybit() {
        Map<String,String> r = new HashMap<>();
        JSONObject j = getJson("https://api.bybit.com/v5/market/instruments-info?category=linear");
        if (j == null) return r;
        JSONObject res = j.optJSONObject("result");
        JSONArray arr = res == null ? null : res.optJSONArray("list");
        if (arr == null) return r;
        for (int i=0;i<arr.length();i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (!"USDT".equalsIgnoreCase(o.optString("quoteCoin"))) continue;
            String sym = o.optString("symbol", null);
            if (sym == null) continue;
            String can = canonical(sym);
            r.put(can, sym);
        }
        return r;
    }

    private Map<String,String> discoverKuCoin() {
        Map<String,String> r = new HashMap<>();
        JSONObject j = getJson("https://api-futures.kucoin.com/api/v1/contracts/active");
        if (j == null) return r;
        JSONArray arr = j.optJSONArray("data");
        if (arr == null) return r;
        for (int i=0;i<arr.length();i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (!"USDT".equalsIgnoreCase(o.optString("quoteCurrency"))) continue;
            if (o.optBoolean("isInverse", false)) continue;
            String sym = o.optString("symbol", null);         // e.g. XBTUSDTM
            if (sym == null) continue;
            String fix = sym.toUpperCase(Locale.ROOT).replace("XBT","BTC");
            if (fix.endsWith("USDTM")) fix = fix.substring(0, fix.length()-1);
            String can = canonical(fix);
            r.put(can, sym);
        }
        return r;
    }

    private Map<String,String> discoverGate() {
        Map<String,String> r = new HashMap<>();
        JSONArray arr = getJsonArray("https://api.gateio.ws/api/v4/futures/usdt/contracts");
        if (arr == null) return r;
        for (int i=0;i<arr.length();i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (!"perpetual".equalsIgnoreCase(o.optString("type"))) continue;
            String name = o.optString("name", null); // BTC_USDT
            if (name == null) continue;
            String can = canonical(name);
            r.put(can, name);
        }
        return r;
    }

    private Map<String,String> discoverBitget() {
        Map<String,String> r = new HashMap<>();
        JSONObject j = getJson("https://api.bitget.com/api/mix/v1/market/contracts?productType=umcbl");
        if (j == null) return r;
        JSONArray arr = j.optJSONArray("data");
        if (arr == null) return r;
        for (int i=0;i<arr.length();i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String sym = o.optString("symbol", null); // usually like BTCUSDT_UMCBL
            if (sym == null) continue;
            // Canonical from the base part before underscore:
            String base = sym.toUpperCase(Locale.ROOT);
            if (base.contains("_")) base = base.substring(0, base.indexOf('_'));
            String can = canonical(base);
            r.put(can, sym); // keep native (with suffix) for price fetch
        }
        return r;
    }

    private Map<String,String> discoverMexc() {
        Map<String,String> r = new HashMap<>();
        JSONObject j = getJson("https://contract.mexc.com/api/v1/contract/detail");
        if (j == null) return r;
        JSONArray arr = j.optJSONArray("data");
        if (arr == null) return r;
        for (int i=0;i<arr.length();i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (!"USDT".equalsIgnoreCase(o.optString("quoteCurrency"))) continue;
            if (!"PERPETUAL".equalsIgnoreCase(o.optString("type"))) continue;
            String sym = o.optString("symbol", null); // BTC_USDT
            if (sym == null) continue;
            String can = canonical(sym);
            r.put(can, sym);
        }
        return r;
    }

    // Canonical form: remove non-alphanumerics, upper case, fix XBT->BTC, strip trailing 'M'
    private static String canonical(String raw) {
        String s = raw.toUpperCase(Locale.ROOT)
                .replace("XBT","BTC")
                .replaceAll("[^A-Z0-9]", "");
        if (s.endsWith("USDTM")) s = s.substring(0, s.length()-1);
        int i = s.indexOf("USDT");
        return (i > 0) ? s.substring(0, i + 4) : s; // ensure ends with USDT if present
    }

    // --------------------- Price fetch (defensive) ---------------------
    private Quote fetchBinance(String nativeSymbol) {
        JSONObject j = getJson("https://fapi.binance.com/fapi/v1/ticker/price?symbol=" + enc(nativeSymbol));
        if (j == null) return null;
        String price = j.optString("price", null);
        if (price == null) return null;
        return safeQuote("Binance", nativeSymbol, price);
    }

    private Quote fetchBybit(String nativeSymbol) {
        JSONObject j = getJson("https://api.bybit.com/v5/market/tickers?category=linear&symbol=" + enc(nativeSymbol));
        if (j == null) return null;
        JSONObject res = j.optJSONObject("result");
        JSONArray arr = res == null ? null : res.optJSONArray("list");
        if (arr == null || arr.isEmpty()) return null;
        JSONObject o = arr.optJSONObject(0);
        if (o == null) return null;
        String price = o.optString("lastPrice", null);
        if (price == null) return null;
        return safeQuote("Bybit", nativeSymbol, price);
    }

    private Quote fetchKuCoin(String nativeSymbol) {
        // Use contracts/active (same as discovery), look up the same symbol and read lastTradePrice
        JSONObject j = getJson("https://api-futures.kucoin.com/api/v1/contracts/active");
        if (j == null) return null;
        JSONArray arr = j.optJSONArray("data");
        if (arr == null) return null;
        for (int i=0;i<arr.length();i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (!nativeSymbol.equalsIgnoreCase(o.optString("symbol"))) continue;
            String price = o.optString("lastTradePrice", null);
            if (price == null) return null;
            return safeQuote("KuCoin", nativeSymbol, price);
        }
        return null;
    }

    private Quote fetchGate(String nativeSymbol) {
        JSONArray arr = getJsonArray("https://api.gateio.ws/api/v4/futures/usdt/tickers?contract=" + enc(nativeSymbol));
        if (arr == null || arr.isEmpty()) return null;
        JSONObject o = arr.optJSONObject(0);
        if (o == null) return null;
        String price = o.optString("last", null);
        if (price == null) return null;
        return safeQuote("Gate", nativeSymbol, price);
    }

    private Quote fetchBitget(String nativeSymbol) {
        // nativeSymbol from discovery is already like BTCUSDT_UMCBL
        JSONObject j = getJson("https://api.bitget.com/api/mix/v1/market/ticker?symbol=" + enc(nativeSymbol));
        if (j == null) return null;
        JSONObject d = j.optJSONObject("data");
        if (d == null) return null;
        String price = d.optString("last", null);
        if (price == null) return null;
        return safeQuote("Bitget", nativeSymbol, price);
    }

    private Quote fetchMexc(String nativeSymbol) {
        JSONObject j = getJson("https://contract.mexc.com/api/v1/contract/ticker?symbol=" + enc(nativeSymbol));
        if (j == null) return null;
        JSONArray arr = j.optJSONArray("data");
        if (arr == null || arr.isEmpty()) return null;
        JSONObject o = arr.optJSONObject(0);
        if (o == null) return null;
        String price = o.optString("lastPrice", null);
        if (price == null) return null;
        return safeQuote("MEXC", nativeSymbol, price);
    }

    private Quote fetchBingX(String nativeSymbol) {
        JSONObject j = getJson("https://open-api.bingx.com/openApi/swap/v2/quote/price?symbol=" + enc(nativeSymbol));
        if (j == null) return null;
        JSONObject d = j.optJSONObject("data");
        if (d == null) return null;
        String price = d.optString("price", null);
        if (price == null) return null;
        return safeQuote("BingX", nativeSymbol, price);
    }

    private Quote fetchHTX(String nativeSymbol) {
        JSONObject j = getJson("https://api.hbdm.com/linear-swap-ex/market/detail/merged?contract_code=" + enc(nativeSymbol));
        if (j == null) return null;
        JSONObject tick = j.optJSONObject("tick");
        if (tick == null) return null;
        String price = String.valueOf(tick.opt("close"));
        if (price == null || "null".equalsIgnoreCase(price)) return null;
        return safeQuote("HTX", nativeSymbol, price);
    }

    private Quote safeQuote(String exch, String nativeSymbol, String priceStr) {
        try {
            BigDecimal px = new BigDecimal(priceStr);
            return new Quote(exch, nativeSymbol, px);
        } catch (Exception e) {
            // bad number format – ignore this quote
            return null;
        }
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
            try { return new JSONObject(body); }
            catch (Exception e) { return null; } // not JSON
        } catch (IOException e) { return null; }
    }

    private JSONArray getJsonArray(String url) {
        try (Response r = http.newCall(new Request.Builder().url(url).get().build()).execute()) {
            if (!r.isSuccessful() || r.body() == null) return null;
            String body = r.body().string();
            if (body == null || body.isEmpty()) return null;
            try { return new JSONArray(body); }
            catch (Exception e) { return null; } // not JSON array
        } catch (IOException e) { return null; }
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}



