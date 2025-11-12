package com.hamstergroup.evilhamster.service;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
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
 * CEX + Perp DEX spread tracker.
 * New: Hyperliquid (full), Aster (stub), Lighter (stub).
 *
 * Notes:
 * • Hyperliquid discovery uses POST https://api.hyperliquid.xyz/info {"type":"meta"} (coin universe),
 *   prices via {"type":"allMids"} (mark prices). We group them as USDT perps for comparison.
 * • Aster/Lighter are added as placeholders (derived symbols + link builders). When you share a
 *   public ticker endpoint, we can wire them to return quotes (now they return null, i.e. ignored).
 */
public class FuturesPriceSpreadTracker {

    private final OkHttpClient http = new OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(10))
            .build();

    // refresh universe every 10 minutes
    private volatile Map<String, Map<String, SymbolInfo>> universe = new ConcurrentHashMap<>();
    private volatile long lastRefresh = 0L;
    private static final long SYMBOL_REFRESH_MS = 10 * 60_000L; // 10m

    // cache for Hyperliquid universe -> index
    private volatile List<String> hlUniverse = List.of();                 // coin order
    private volatile Map<String, Integer> hlIndexByCoin = Map.of();       // coin -> index

    // Known aliases to avoid ticker-name collisions (example: Bitget VELO = Velodrome)
    private static final Map<String,String> ALIASES = new HashMap<>();
    static {
        ALIASES.put("BITGET:VELO", "VELODROME");
    }

    // ===== records =====
    public record Quote(String exch, String symbol, BigDecimal price, Double funding) {}
    public static final class SymbolInfo {
        public final String exchange, nativeSymbol, base, quote, longName;
        public SymbolInfo(String exchange, String nativeSymbol, String base, String quote, String longName) {
            this.exchange = exchange; this.nativeSymbol = nativeSymbol; this.base = base; this.quote = quote; this.longName = longName;
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

    /** Scan all discovered instruments; prices compared inside each (ticker,longName) group. */
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
                    case "Binance"      -> fetchBinance(si.nativeSymbol);
                    case "Bybit"        -> fetchBybit(si.nativeSymbol);
                    case "KuCoin"       -> fetchKuCoin(si.nativeSymbol);
                    case "Gate"         -> fetchGate(si.nativeSymbol);
                    case "Bitget"       -> fetchBitget(si.nativeSymbol);
                    case "MEXC"         -> fetchMexc(si.nativeSymbol);
                    case "BingX"        -> fetchBingX(si.nativeSymbol);
                    case "HTX"          -> fetchHTX(si.nativeSymbol);
                    // Perp DEXes:
                    case "Hyperliquid"  -> fetchHyperliquid(si.nativeSymbol);
                    case "Aster"        -> fetchAster(si.nativeSymbol);    // stub (returns null)
                    case "Lighter"      -> fetchLighter(si.nativeSymbol);  // stub (returns null)
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
                String alias = ALIASES.get((exch + ":" + si.base).toUpperCase(Locale.ROOT));
                String longNameNorm = normalizeLongName(alias != null ? alias : si.longName, si.base);
                // Group everyone under <BASE>USDT so CEX USDT-perps compare with USD/USDC quote DEXes
                String canonicalTicker = si.base.toUpperCase(Locale.ROOT) + "USDT";
                String groupKey = canonicalTicker + "§" + longNameNorm;
                map.computeIfAbsent(groupKey, k -> new ConcurrentHashMap<>())
                        .put(exch, new SymbolInfo(exch, si.nativeSymbol, si.base, "USDT", longNameNorm));
            }
        };

        // CEXes
        mergeList.accept("Binance", discoverBinance());
        mergeList.accept("Bybit",   discoverBybit());
        mergeList.accept("KuCoin",  discoverKuCoin());
        mergeList.accept("Gate",    discoverGate());
        mergeList.accept("Bitget",  discoverBitget());
        mergeList.accept("MEXC",    discoverMexc());

        // Perp DEXes
        mergeList.accept("Hyperliquid", discoverHyperliquid());  // ✅ full
        mergeList.accept("Aster",       discoverAster());        // 🚧 stub
        mergeList.accept("Lighter",     discoverLighter());      // 🚧 stub

        // Derive BingX/HTX native symbols from canonical groups so they can join when present
        Map<String, Map<String, SymbolInfo>> derived = new HashMap<>(map);
        for (String groupKey : new ArrayList<>(map.keySet())) {
            String canonical = groupKey.substring(0, groupKey.indexOf('§'));
            String base = canonical.replace("USDT", "");
            String longName = groupKey.substring(groupKey.indexOf('§') + 1);

            derived.computeIfAbsent(groupKey, k -> new ConcurrentHashMap<>())
                    .putIfAbsent("BingX", new SymbolInfo("BingX", base + "-USDT", base, "USDT", longName));
            derived.get(groupKey)
                    .putIfAbsent("HTX",   new SymbolInfo("HTX",   base + "-USDT", base, "USDT", longName));
        }

        universe = derived;
        lastRefresh = now;
    }

    private static String normalizeLongName(String longName, String baseFallback) {
        String s = (longName == null || longName.isBlank()) ? baseFallback : longName;
        return s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    // ----- discovery per exchange (return SymbolInfo list) -----
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
            r.add(new SymbolInfo("Binance", sym, base, "USDT", base));
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
            String sym  = o.optString("symbol", null);          // may end with M
            String base = o.optString("baseCurrency", null);
            if (base != null) base = base.replace("XBT","BTC");
            if (sym == null || base == null) continue;
            r.add(new SymbolInfo("KuCoin", sym, base, "USDT", base));
        }
        return r;
    }
    private List<SymbolInfo> discoverGate() {
        List<SymbolInfo> r = new ArrayList<>();
        JSONArray arr = getJsonArray("https://api.gateio.ws/api/v4/futures/usdt/contracts");
        if (arr == null) return r;
        for (int i=0;i < arr.length();i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (!"perpetual".equalsIgnoreCase(o.optString("type"))) continue;
            String name = o.optString("name", null);         // BTC_USDT
            if (name == null) continue;
            String base = name.replace("_USDT","").replace("-USDT","").replace("/USDT","");
            r.add(new SymbolInfo("Gate", name, base, "USDT", base));
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
            String sym  = o.optString("symbol", null);          // e.g., VELOUSDT_UMCBL
            String base = o.optString("baseCoin", null);
            if (sym == null || base == null) continue;
            String alias = ALIASES.get(("BITGET:" + base).toUpperCase(Locale.ROOT));
            r.add(new SymbolInfo("Bitget", sym, base, "USDT", alias != null ? alias : base));
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
            String base = o.optString("baseCurrency", null);
            if (sym == null || base == null) continue;
            r.add(new SymbolInfo("MEXC", sym, base, "USDT", base));
        }
        return r;
    }

    /** ✅ Hyperliquid discovery – coins via "meta". We group them as USDT so they compare with CEX perps. */
    private List<SymbolInfo> discoverHyperliquid() {
        List<SymbolInfo> list = new ArrayList<>();
        JSONObject meta = postJson("https://api.hyperliquid.xyz/info", new JSONObject().put("type", "meta"));
        if (meta == null) return list;
        JSONArray uni = meta.optJSONArray("universe");
        if (uni == null) return list;

        List<String> coins = new ArrayList<>();
        Map<String,Integer> idx = new HashMap<>();
        for (int i=0;i<uni.length();i++) {
            String coin = uni.optString(i, null);
            if (coin == null) continue;
            coins.add(coin);
            idx.put(coin, i);
            // nativeSymbol = coin (e.g., "BTC")
            list.add(new SymbolInfo("Hyperliquid", coin, coin, "USDT", coin));
        }
        hlUniverse = coins;
        hlIndexByCoin = idx;
        return list;
    }

    /** 🚧 Aster placeholder – join universe with derived symbol; implement price fetch once endpoint is known. */
    private List<SymbolInfo> discoverAster() {
        // We only create entries if the base coin already exists elsewhere (to avoid lonely groups).
        List<SymbolInfo> r = new ArrayList<>();
        for (String groupKey : universe.keySet()) {
            String canonical = groupKey.substring(0, groupKey.indexOf('§'));
            String base = canonical.replace("USDT", "");
            r.add(new SymbolInfo("Aster", base, base, "USDT", base));
        }
        return r;
    }

    /** 🚧 Lighter placeholder – same approach as Aster. */
    private List<SymbolInfo> discoverLighter() {
        List<SymbolInfo> r = new ArrayList<>();
        for (String groupKey : universe.keySet()) {
            String canonical = groupKey.substring(0, groupKey.indexOf('§'));
            String base = canonical.replace("USDT", "");
            r.add(new SymbolInfo("Lighter", base, base, "USDT", base));
        }
        return r;
    }

    // --------------------- Price + Funding (defensive) ---------------------
    private Quote fetchBinance(String sym) {
        JSONObject j = getJson("https://fapi.binance.com/fapi/v1/ticker/price?symbol=" + enc(sym));
        if (j == null) return null;
        String p = j.optString("price", null);
        Double fr = null;
        JSONObject f = getJson("https://fapi.binance.com/fapi/v1/premiumIndex?symbol=" + enc(sym));
        if (f != null) {
            String s = f.optString("lastFundingRate", null);
            if (s != null) try { fr = Double.parseDouble(s); } catch (Exception ignore) {}
        }
        return p == null ? null : safeQuote("Binance", sym, p, fr);
    }
    private Quote fetchBybit(String sym) {
        JSONObject j = getJson("https://api.bybit.com/v5/market/tickers?category=linear&symbol=" + enc(sym));
        if (j == null) return null;
        JSONObject res = j.optJSONObject("result");
        JSONArray arr = res == null ? null : res.optJSONArray("list");
        if (arr == null || arr.isEmpty()) return null;
        JSONObject o = arr.optJSONObject(0);
        String p = o.optString("lastPrice", null);
        Double fr = null;
        String frs = o.optString("fundingRate", null);
        if (frs != null) try { fr = Double.parseDouble(frs); } catch (Exception ignore) {}
        return p == null ? null : safeQuote("Bybit", sym, p, fr);
    }
    private Quote fetchKuCoin(String sym) {
        JSONObject j = getJson("https://api-futures.kucoin.com/api/v1/contracts/active");
        if (j == null) return null;
        JSONArray arr = j.optJSONArray("data");
        if (arr == null) return null;
        String p = null; Double fr = null;
        for (int i=0;i<arr.length();i++) {
            JSONObject o = arr.optJSONObject(i); if (o == null) continue;
            if (!sym.equalsIgnoreCase(o.optString("symbol"))) continue;
            p = o.optString("lastTradePrice", null);
            String frs = o.optString("fundingFeeRate", null);
            if (frs == null) frs = o.optString("fundingRate", null);
            if (frs != null) try { fr = Double.parseDouble(frs); } catch (Exception ignore) {}
            break;
        }
        return p == null ? null : safeQuote("KuCoin", sym, p, fr);
    }
    private Quote fetchGate(String sym) {
        JSONArray arr = getJsonArray("https://api.gateio.ws/api/v4/futures/usdt/tickers?contract=" + enc(sym));
        if (arr == null || arr.isEmpty()) return null;
        JSONObject o = arr.optJSONObject(0);
        String p = o.optString("last", null);
        Double fr = null;
        String frs = o.optString("funding_rate", null);
        if (frs != null) try { fr = Double.parseDouble(frs); } catch (Exception ignore) {}
        return p == null ? null : safeQuote("Gate", sym, p, fr);
    }
    private Quote fetchBitget(String sym) {
        JSONObject j = getJson("https://api.bitget.com/api/mix/v1/market/ticker?symbol=" + enc(sym));
        if (j == null) return null;
        JSONObject d = j.optJSONObject("data");
        String p = d == null ? null : d.optString("last", null);
        Double fr = null;
        JSONObject f = getJson("https://api.bitget.com/api/mix/v1/market/currentFundingRate?symbol=" + enc(sym));
        if (f != null) {
            JSONObject fd = f.optJSONObject("data");
            if (fd != null) {
                String frs = fd.optString("fundingRate", null);
                if (frs != null) try { fr = Double.parseDouble(frs); } catch (Exception ignore) {}
            }
        }
        return p == null ? null : safeQuote("Bitget", sym, p, fr);
    }
    private Quote fetchMexc(String sym) {
        JSONObject j = getJson("https://contract.mexc.com/api/v1/contract/ticker?symbol=" + enc(sym));
        if (j == null) return null;
        JSONArray arr = j.optJSONArray("data");
        String p = null;
        if (arr != null && !arr.isEmpty()) {
            JSONObject o = arr.optJSONObject(0);
            p = o == null ? null : o.optString("lastPrice", null);
        }
        Double fr = null;
        JSONObject f = getJson("https://contract.mexc.com/api/v1/contract/fundingRate?symbol=" + enc(sym));
        if (f != null) {
            JSONObject fd = f.optJSONObject("data");
            if (fd != null) {
                String frs = fd.optString("fundingRate", null);
                if (frs != null) try { fr = Double.parseDouble(frs); } catch (Exception ignore) {}
            }
        }
        return p == null ? null : safeQuote("MEXC", sym, p, fr);
    }
    private Quote fetchBingX(String sym) {
        JSONObject j = getJson("https://open-api.bingx.com/openApi/swap/v2/quote/price?symbol=" + enc(sym));
        String p = null;
        if (j != null) {
            JSONObject d = j.optJSONObject("data");
            p = d == null ? null : d.optString("price", null);
        }
        Double fr = null;
        JSONObject f = getJson("https://open-api.bingx.com/openApi/swap/v2/market/fundingRate?symbol=" + enc(sym));
        if (f != null) {
            JSONObject d = f.optJSONObject("data");
            if (d != null) {
                String frs = d.optString("fundingRate", null);
                if (frs != null) try { fr = Double.parseDouble(frs); } catch (Exception ignore) {}
            }
        }
        return p == null ? null : safeQuote("BingX", sym, p, fr);
    }
    private Quote fetchHTX(String sym) {
        JSONObject j = getJson("https://api.hbdm.com/linear-swap-ex/market/detail/merged?contract_code=" + enc(sym));
        String p = null;
        if (j != null) {
            JSONObject tick = j.optJSONObject("tick");
            if (tick != null) {
                Object close = tick.opt("close");
                p = close == null ? null : String.valueOf(close);
            }
        }
        Double fr = null;
        JSONObject f = getJson("https://api.hbdm.com/linear-swap-api/v1/swap_funding_rate?contract_code=" + enc(sym));
        if (f != null) {
            JSONObject fd = f.optJSONObject("data");
            if (fd != null) {
                String frs = fd.optString("funding_rate", null);
                if (frs != null) try { fr = Double.parseDouble(frs); } catch (Exception ignore) {}
            }
        }
        return p == null ? null : safeQuote("HTX", sym, p, fr);
    }

    /** ✅ Hyperliquid price: POST {"type":"allMids"} then find by coin index. Funding left null for now. */
    private Quote fetchHyperliquid(String coin) {
        if (hlUniverse.isEmpty() || hlIndexByCoin.isEmpty()) {
            // try to (re)discover quickly
            discoverHyperliquid();
        }
        Integer idx = hlIndexByCoin.get(coin);
        if (idx == null) return null;

        JSONObject mids = postJson("https://api.hyperliquid.xyz/info", new JSONObject().put("type", "allMids"));
        if (mids == null) return null;
        JSONArray arr = mids.optJSONArray("universeMids");
        if (arr == null || idx >= arr.length()) return null;
        String price = arr.optString(idx, null);
        return price == null ? null : safeQuote("Hyperliquid", coin, price, null);
    }

    /** 🚧 Aster/Lighter fetchers (return null until endpoints are provided). */
    private Quote fetchAster(String nativeSymbol)   { return null; }
    private Quote fetchLighter(String nativeSymbol) { return null; }

    private Quote safeQuote(String exch, String nativeSymbol, String priceStr, Double funding) {
        try {
            BigDecimal px = new BigDecimal(priceStr);
            return new Quote(exch, nativeSymbol, px, funding);
        } catch (Exception e) { return null; }
    }

    // --------------------- Contract page URLs ---------------------
    public String exchangeUrl(String exch, String canonical) {
        String base = canonical;
        return switch (exch) {
            case "Binance"     -> "https://www.binance.com/en/futures/" + base;
            case "Bybit"       -> "https://www.bybit.com/trade/usdt/" + base;
            case "KuCoin"      -> "https://futures.kucoin.com/trade/" + base;
            case "Gate"        -> "https://www.gate.io/futures_trade/USDT/" + base;
            case "Bitget"      -> "https://www.bitget.com/futures/usdt/" + base;
            case "MEXC"        -> "https://futures.mexc.com/exchange/" + base;
            case "BingX"       -> "https://bingx.com/en-us/futures/" + base.toLowerCase();
            case "HTX"         -> "https://futures.htx.com/en-us/usdt_swap/exchange/" + base.toLowerCase();
            // Perp DEX UIs
            case "Hyperliquid" -> "https://app.hyperliquid.xyz/trade/" + base.replace("USDT",""); // e.g., BTC
            case "Aster"       -> "https://app.aster.exchange/";   // TODO: direct pair link when known
            case "Lighter"     -> "https://app.lighter.xyz/";      // TODO: direct pair link when known
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

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private JSONObject postJson(String url, JSONObject body) {
        try (Response r = http.newCall(new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), JSON))
                .build()).execute()) {
            if (!r.isSuccessful() || r.body() == null) return null;
            String s = r.body().string();
            if (s == null || s.isEmpty()) return null;
            try { return new JSONObject(s); } catch (Exception e) { return null; }
        } catch (IOException e) { return null; }
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}






