package com.tickexporter;

import com.dukascopy.api.*;
import com.dukascopy.api.system.ClientFactory;
import com.dukascopy.api.system.IClient;
import com.dukascopy.api.system.ISystemListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Standalone JForex tick exporter.
 * Parameters (system properties or env):
 *   instrument   e.g. EURUSD
 *   from         yyyy-MM-dd or yyyy-MM-dd HH:mm  (UTC)
 *   to           yyyy-MM-dd or yyyy-MM-dd HH:mm  (UTC, exclusive)
 *   username / password  (or DUKASCOPY_USERNAME / DUKASCOPY_PASSWORD)
 *   outputDir    default: ./output
 */
public class TickExporterMain {

    private static final Logger log = LoggerFactory.getLogger(TickExporterMain.class);

    // DEMO JNLP (update only if Dukascopy changes the URL)
    private static final String JNLP_URL = "https://platform.dukascopy.com/demo_3/jforex_3.jnlp";

    // Optimized: larger chunks + less sleep
    private static final long CHUNK_MS = 24L * 60 * 60 * 1000; // 24 hours
    private static final long SLEEP_BETWEEN_CHUNKS_MS = 50;

    public static void main(String[] args) throws Exception {
        String instrumentStr = firstNonBlank(
                System.getProperty("instrument"),
                System.getenv("INSTRUMENT"),
                "EURUSD");
        String fromStr = firstNonBlank(
                System.getProperty("from"),
                System.getenv("FROM"),
                null);
        String toStr = firstNonBlank(
                System.getProperty("to"),
                System.getenv("TO"),
                null);
        String username = firstNonBlank(
                System.getProperty("username"),
                System.getenv("DUKASCOPY_USERNAME"),
                null);
        String password = firstNonBlank(
                System.getProperty("password"),
                System.getenv("DUKASCOPY_PASSWORD"),
                null);
        String outputDir = firstNonBlank(
                System.getProperty("outputDir"),
                System.getenv("OUTPUT_DIR"),
                "./output");

        if (fromStr == null || toStr == null) {
            System.err.println("ERROR: 'from' and 'to' are required (yyyy-MM-dd or yyyy-MM-dd HH:mm UTC)");
            System.exit(2);
        }
        if (username == null || password == null) {
            System.err.println("ERROR: DUKASCOPY_USERNAME and DUKASCOPY_PASSWORD are required");
            System.exit(2);
        }

        Instrument instrument = parseInstrument(instrumentStr);
        long from = parseUtc(fromStr);
        long to = parseUtc(toStr);

        if (from >= to) {
            System.err.println("ERROR: from must be before to");
            System.exit(2);
        }

        long days = TimeUnit.MILLISECONDS.toDays(to - from);
        log.info("Instrument : {}", instrument);
        log.info("From (UTC) : {}", formatUtc(from));
        log.info("To   (UTC) : {} (exclusive)", formatUtc(to));
        log.info("Approx days: {}", days);
        if (days > 400) {
            log.warn("Range is larger than ~1 year. This may exceed GitHub Actions time/disk limits.");
        }

        File outDir = new File(outputDir);
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new RuntimeException("Cannot create output directory: " + outDir.getAbsolutePath());
        }

        String safeFrom = formatUtc(from).replace(':', '-').replace(' ', '_');
        String safeTo = formatUtc(to).replace(':', '-').replace(' ', '_');
        File csvFile = new File(outDir, instrument.name() + "_" + safeFrom + "_to_" + safeTo + ".csv");

        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicBoolean success = new AtomicBoolean(false);

        final IClient client = ClientFactory.getDefaultInstance();

        client.setSystemListener(new ISystemListener() {
            private int lightReconnects = 3;

            @Override
            public void onStart(long processId) {
                log.info("Strategy process started: {}", processId);
            }

            @Override
            public void onStop(long processId) {
                log.info("Strategy process stopped: {}", processId);
                finished.countDown();
            }

            @Override
            public void onConnect() {
                log.info("Connected to Dukascopy");
                lightReconnects = 3;
            }

            @Override
            public void onDisconnect() {
                log.warn("Disconnected from Dukascopy");
                if (lightReconnects > 0) {
                    lightReconnects--;
                    try {
                        client.reconnect();
                    } catch (Exception e) {
                        log.error("Reconnect failed", e);
                    }
                } else {
                    finished.countDown();
                }
            }
        });

        log.info("Connecting...");
        client.connect(JNLP_URL, username, password);

        int waitSec = 30;
        while (waitSec-- > 0 && !client.isConnected()) {
            Thread.sleep(1000);
        }
        if (!client.isConnected()) {
            log.error("Failed to connect to Dukascopy servers");
            System.exit(1);
        }

        Set<Instrument> instruments = new HashSet<>();
        instruments.add(instrument);
        client.setSubscribedInstruments(instruments);
        // small wait so subscription is ready
        Thread.sleep(3000);

                long processId = client.startStrategy(new TickExportStrategy(instrument, from, to, csvFile, success));

        // Wait for strategy to finish
        boolean completed = finished.await(5, TimeUnit.HOURS);
        if (!completed) {
            log.error("Timed out waiting for export to finish");
            try { client.stopStrategy(processId); } catch (Exception ignored) {}
            System.exit(1);
        }

        if (!success.get() || !csvFile.exists() || csvFile.length() == 0) {
            log.error("Export failed or produced empty file");
            try { client.disconnect(); } catch (Exception ignored) {}
            System.exit(1);
        }

        log.info("CSV ready: {} ({} bytes)", csvFile.getAbsolutePath(), csvFile.length());
        System.out.println("OUTPUT_CSV=" + csvFile.getAbsolutePath());

        // disconnect را غیرمسدودکننده انجام بده
        Thread disconnectThread = new Thread(() -> {
            try {
                client.disconnect();
            } catch (Exception ignored) {}
        });
        disconnectThread.setDaemon(true);
        disconnectThread.start();
        try {
            disconnectThread.join(5000);
        } catch (InterruptedException ignored) {}

        System.exit(0);
    }

    // ==================== Strategy ====================

    static class TickExportStrategy implements IStrategy {
        private final Instrument instrument;
        private final long from;
        private final long to;
        private final File csvFile;
        private final AtomicBoolean success;

        private IHistory history;
        private IConsole console;

        TickExportStrategy(Instrument instrument, long from, long to, File csvFile, AtomicBoolean success) {
            this.instrument = instrument;
            this.from = from;
            this.to = to;
            this.csvFile = csvFile;
            this.success = success;
        }

        @Override
        public void onStart(IContext context) throws JFException {
            history = context.getHistory();
            console = context.getConsole();

            PrintWriter out = null;
            try {
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
                fmt.setTimeZone(TimeZone.getTimeZone("GMT"));

                // Buffered writer for much faster I/O
                out = new PrintWriter(new BufferedWriter(new FileWriter(csvFile), 1024 * 1024), false);
                out.println("GmtTime,Bid,Ask,BidVolume,AskVolume");

                long cursor = from;
                long lastTickTime = -1;
                long total = 0;
                long startMs = System.currentTimeMillis();

                while (cursor < to) {
                    long chunkEnd = Math.min(cursor + CHUNK_MS, to);

                    List<ITick> ticks = history.getTicks(instrument, cursor, chunkEnd);

                    for (ITick t : ticks) {
                        if (t.getTime() <= lastTickTime) continue;
                        // exclusive end
                        if (t.getTime() >= to) continue;
                        lastTickTime = t.getTime();

                        out.println(fmt.format(new Date(t.getTime())) + ","
                                + t.getBid() + ","
                                + t.getAsk() + ","
                                + t.getBidVolume() + ","
                                + t.getAskVolume());
                        total++;
                    }

                    long elapsedSec = (System.currentTimeMillis() - startMs) / 1000;
                    String msg = String.format("chunk %s | ticks=%d | total=%d | elapsed=%ds",
                            fmt.format(new Date(chunkEnd)), ticks.size(), total, elapsedSec);
                    console.getOut().println(msg);
                    log.info(msg);

                    cursor = chunkEnd;
                    if (SLEEP_BETWEEN_CHUNKS_MS > 0) {
                        Thread.sleep(SLEEP_BETWEEN_CHUNKS_MS);
                    }
                }

                out.flush();

                console.getOut().println("FINISHED. total ticks = " + total);
                log.info("FINISHED. total ticks = {}", total);
                success.set(true);

            } catch (Exception e) {
                console.getErr().println("Error: " + e);
                log.error("Export error", e);
                success.set(false);
            } finally {
                if (out != null) out.close();
            }

            context.stop();
        }

        @Override public void onTick(Instrument instrument, ITick tick) {}
        @Override public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) {}
        @Override public void onMessage(IMessage message) {}
        @Override public void onAccount(IAccount account) {}
        @Override public void onStop() {}
    }

    // ==================== helpers ====================

    private static Instrument parseInstrument(String s) {
        String normalized = s.trim().toUpperCase().replace("/", "").replace("_", "");
        try {
            return Instrument.valueOf(normalized);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unknown instrument: " + s
                    + " (use EURUSD, XAUUSD, GBPUSD, ...)", e);
        }
    }

    private static long parseUtc(String s) throws Exception {
        s = s.trim();
        SimpleDateFormat sdf;
        if (s.length() <= 10) {
            sdf = new SimpleDateFormat("yyyy-MM-dd");
        } else {
            sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        }
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        sdf.setLenient(false);
        return sdf.parse(s).getTime();
    }

    private static String formatUtc(long ms) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        return sdf.format(new Date(ms));
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }
}
