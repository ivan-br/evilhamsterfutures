package com.hamstergroup.evilhamster.service;

import com.hamstergroup.evilhamster.config.HamsterConfigProperties;
import org.springframework.stereotype.Component;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.PinChatMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

@Component
public class FuturesPriceSpreadBot extends TelegramLongPollingBot {

    private static final String CB_PREFIX = "SPREAD_SNAPSHOT_TOP_N:";

    private final HamsterConfigProperties properties;

    // ---- formatting
    private static final DecimalFormat DF4;
    static {
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(Locale.US);
        DF4 = new DecimalFormat("0.0000", sym);
        DF4.setRoundingMode(RoundingMode.HALF_UP);
    }
    private static String fmt(BigDecimal v) { return v == null ? "—" : DF4.format(v); }
    private static String fmt(double v)     { return String.format(Locale.US, "%.4f", v); }
    private static String fmtFunding(Double v) {
        if (v == null) return "—";
        double p = v * 100.0; // fraction → percent number like -1.2
        String s = String.format(Locale.US, "%.6f", p);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.isEmpty() ? "0" : s;
    }
    private static String esc(String s)     { return s == null ? "" : s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;"); }

    // tracker + scheduling
    private final FuturesPriceSpreadTracker tracker = new FuturesPriceSpreadTracker();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    // per-chat settings
    private final Map<Long, Double> thresholdsPct = new ConcurrentHashMap<>();
    private final Map<Long, Long> intervalsMs = new ConcurrentHashMap<>();
    private final Map<Long, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    public FuturesPriceSpreadBot(HamsterConfigProperties properties) {
        super(properties.getBotToken());
        this.properties = properties;
    }

    @Override public String getBotUsername() { return properties.getBotName(); }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasCallbackQuery()) { handleCallback(update); return; }
            if (!(update.hasMessage() && update.getMessage().hasText())) return;

            final String text = update.getMessage().getText().trim();
            final long chatId = update.getMessage().getChatId();

            if (text.startsWith("/start")) { sendAndPinWelcomeMessage(chatId); return; }

            if (text.startsWith("/update")) {
                int topN = 10;
                String[] p = text.split("\\s+");
                if (p.length > 1) try { topN = Math.max(1, Integer.parseInt(p[1])); } catch (Exception ignore) {}
                sendHtmlWithUpdateButton(chatId, buildSnapshot(topN), topN);
                return;
            }

            if (text.startsWith("/spread_stop")) {
                cancelTask(chatId);
                thresholdsPct.remove(chatId);
                sendText(chatId, "🔕 Spread alerts stopped.");
                return;
            }

            if (text.startsWith("/spread")) {
                String[] p = text.split("\\s+");
                if (p.length < 2) { sendText(chatId, "Usage: /spread <percent>\nExample: /spread 10"); return; }
                double thr = parsePercent(p[1]); // "10" or "10%"
                thresholdsPct.put(chatId, thr);
                ensureTask(chatId);
                sendText(chatId, "✅ Threshold set to ≥ " + fmt(thr) + "%");
                return;
            }

            if (text.startsWith("/interval")) {
                String[] p = text.split("\\s+");
                if (p.length < 2) { sendText(chatId, "Usage: /interval <time>\nExamples: /interval 30s | 10m | 1h"); return; }
                long ms = parseDurationMs(p[1]);
                intervalsMs.put(chatId, ms);
                ensureTask(chatId);
                sendText(chatId, "⏱️ Scan interval set to " + p[1]);
                return;
            }

            // default – pin sender line like your sample
            sendMessageInfo(chatId, update.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ============== core ==============
    private void ensureTask(long chatId) {
        thresholdsPct.putIfAbsent(chatId, 10.0);     // default 10%
        intervalsMs.putIfAbsent(chatId, 60_000L);    // default 1m
        cancelTask(chatId);

        Runnable r = () -> {
            try {
                double thr = thresholdsPct.get(chatId);
                List<FuturesPriceSpreadTracker.Spread> list = tracker.scanAll(); // auto-discovered symbols
                if (list.isEmpty()) return;

                list.sort(Comparator.comparingDouble(FuturesPriceSpreadTracker.Spread::spreadPct).reversed());
                FuturesPriceSpreadTracker.Spread best = list.get(0);

                if (best.spreadPct() >= thr) {
                    sendHtml(chatId, renderAlert(best, thr));
                }
            } catch (Exception e) { e.printStackTrace(); }
        };

        long period = Math.max(1_000, intervalsMs.get(chatId));
        tasks.put(chatId, scheduler.scheduleAtFixedRate(r, 0, period, TimeUnit.MILLISECONDS));
    }
    private void cancelTask(long chatId) {
        Optional.ofNullable(tasks.remove(chatId)).ifPresent(f -> f.cancel(true));
    }

    // ============== formatting ==============
    private String buildSnapshot(int topN) throws Exception {
        var list = tracker.scanAll();
        list.sort(Comparator.comparingDouble(FuturesPriceSpreadTracker.Spread::spreadPct).reversed());
        if (topN > list.size()) topN = list.size();

        String ts = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC).format(Instant.now());

        StringBuilder sb = new StringBuilder();
        sb.append("<b>🔎 Futures price spread (top ").append(topN).append(")</b>\n")
                .append("<i>UTC: ").append(ts).append("</i>\n\n");

        for (int i=0;i<topN;i++) {
            sb.append(renderBlock(list.get(i))).append("\n");
        }
        return sb.toString();
    }

    private String renderBlock(FuturesPriceSpreadTracker.Spread s) {
        String base = s.symbol().replace("USDT","");
        StringBuilder sb = new StringBuilder();
        sb.append("<b>").append(esc(base)).append("</b> (USDT): ")
                .append(fmt(s.spreadPct())).append("%\n\n");

        // Two exchange lines with links (max/min) + funding
        sb.append(a(s.max())).append(": ").append(fmt(s.max().price()))
                .append(". Funding: ").append(fmtFunding(s.max().funding())).append("\n");
        sb.append(a(s.min())).append(": ").append(fmt(s.min().price()))
                .append(". Funding: ").append(fmtFunding(s.min().funding())).append("\n");

        return sb.toString();
    }

    private String renderAlert(FuturesPriceSpreadTracker.Spread s, double thr) {
        String base = s.symbol().replace("USDT","");
        StringBuilder sb = new StringBuilder();
        sb.append("<b>⚡ Futures spread alert</b>\n")
                .append("<b>").append(esc(base)).append("</b> — Δ <code>")
                .append(fmt(s.spreadPct())).append("%</code> (≥ ").append(fmt(thr)).append("%)\n\n");

        sb.append(a(s.max())).append(": ").append(fmt(s.max().price()))
                .append(". Funding: ").append(fmtFunding(s.max().funding())).append("\n");
        sb.append(a(s.min())).append(": ").append(fmt(s.min().price()))
                .append(". Funding: ").append(fmtFunding(s.min().funding())).append("\n");

        return sb.toString();
    }

    private String a(FuturesPriceSpreadTracker.Quote q) {
        String url = tracker.exchangeUrl(q.exch(), q.symbol()); // canonical link
        return "<a href=\"" + esc(url) + "\">" + esc(q.exch()) + "</a>";
    }

    // ============== callbacks & telegram helpers ==============
    private void handleCallback(Update update) {
        var cb = update.getCallbackQuery();
        String data = Optional.ofNullable(cb.getData()).orElse("");
        if (!data.startsWith(CB_PREFIX)) return;

        int topN = 10;
        try { topN = Integer.parseInt(data.substring(CB_PREFIX.length())); } catch (Exception ignore) {}

        try {
            String html = buildSnapshot(topN);
            execute(EditMessageText.builder()
                    .chatId(cb.getMessage().getChatId().toString())
                    .messageId(cb.getMessage().getMessageId())
                    .parseMode(ParseMode.HTML)
                    .disableWebPagePreview(true)
                    .text(html)
                    .replyMarkup(updateKeyboard(topN))
                    .build());
            execute(AnswerCallbackQuery.builder().callbackQueryId(cb.getId()).build());
        } catch (Exception e) {
            try {
                execute(AnswerCallbackQuery.builder()
                        .callbackQueryId(cb.getId())
                        .text("Update failed: " + e.getMessage())
                        .showAlert(true)
                        .build());
            } catch (TelegramApiException ignore) {}
        }
    }

    private void sendAndPinWelcomeMessage(Long chatId) {
        try {
            String instruction = """
                Commands:
                • /update [N] — show top-N spreads right now
                • <code>/spread &lt;percent&gt;</code> — alert when spread ≥ percent (e.g. <code>/spread 10</code>)
                • <code>/interval &lt;time&gt;</code> — scan period (e.g. <code>/interval 30s</code> | <code>10m</code> | <code>1h</code>)
                • /spread_stop — stop alerts

                Notes: scans ALL available USDT-perp futures across Binance, Bybit, KuCoin, Gate, Bitget, MEXC,
                plus Hyperliquid (DEX). BingX, HTX, XT, LBank, Aster, Lighter are linked when available.
                """;
            var response = execute(SendMessage.builder()
                    .chatId(chatId)
                    .parseMode(ParseMode.HTML)
                    .disableWebPagePreview(true)
                    .text(instruction)
                    .build());
            execute(PinChatMessage.builder().chatId(chatId).messageId(response.getMessageId()).build());
        } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private void sendHtmlWithUpdateButton(Long chatId, String html, int topN) {
        try {
            execute(SendMessage.builder()
                    .chatId(chatId)
                    .parseMode(ParseMode.HTML)
                    .disableWebPagePreview(true)
                    .text(html)
                    .replyMarkup(updateKeyboard(topN))
                    .build());
        } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private InlineKeyboardMarkup updateKeyboard(int topN) {
        InlineKeyboardButton btn = InlineKeyboardButton.builder()
                .text("🔄 Update")
                .callbackData(CB_PREFIX + topN)
                .build();
        InlineKeyboardMarkup kb = new InlineKeyboardMarkup();
        kb.setKeyboard(List.of(List.of(btn)));
        return kb;
    }

    private void sendHtml(Long chatId, String html) {
        try {
            execute(SendMessage.builder()
                    .chatId(chatId)
                    .parseMode(ParseMode.HTML)
                    .disableWebPagePreview(true)
                    .text(html)
                    .build());
        } catch (TelegramApiException e) { e.printStackTrace(); }
    }
    private void sendText(Long chatId, String text) {
        try { execute(SendMessage.builder().chatId(chatId).text(text).build()); }
        catch (TelegramApiException e) { e.printStackTrace(); }
    }
    private void sendMessageInfo(Long chatId, Message message) {
        SendMessage m = new SendMessage();
        m.setChatId(chatId);
        m.setText(Optional.ofNullable(message.getForwardFrom()).map(User::toString).orElse("empty user"));
        try {
            var resp = execute(m);
            execute(PinChatMessage.builder().chatId(chatId).messageId(resp.getMessageId()).build());
        } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    // ---- parsers ----
    private static long parseDurationMs(String token) {
        String t = token.trim().toLowerCase(Locale.ROOT);
        var m = Pattern.compile("^([0-9]+)([smh]?)$").matcher(t);
        if (!m.matches()) throw new IllegalArgumentException("bad duration");
        long v = Long.parseLong(m.group(1));
        String u = m.group(2);
        if ("s".equals(u)) return v * 1000L;
        if ("h".equals(u)) return v * 3_600_000L;
        return v * 60_000L; // default minutes (also 'm' or empty)
    }
    private static double parsePercent(String token) {
        return Double.parseDouble(token.trim().replace("%","").replace(",","."));
    }
}


