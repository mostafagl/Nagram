package org.telegram.utils.proxy;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * V2Ray subscriptions: URLs that serve a list of share links (plain or base64, the v2rayN format
 * used by most panels and GitHub-hosted lists). An update downloads the list, skips links that
 * are invalid or duplicates of configs already saved (from any subscription or added by hand),
 * tests the new ones through the real Xray core, and saves working ones until the subscription
 * holds its maximum. Configs are never removed by an update, so a failed download or a bad
 * network costs nothing; the configs live in the normal proxy list.
 */
public final class V2RaySubscriptions {

    private static final String PREF = "parva_v2ray_subscriptions";
    private static final int JOB_ID = 0x50415256; // "PARV"
    private static final int MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024;
    private static final int MAX_LINKS = 20000;
    private static final int TCP_TIMEOUT_MS = 3000;
    private static final int TEST_THREADS = 4;
    private static final int TCP_THREADS = 16;
    public static final int[] INTERVALS_MINUTES = {0, 15, 30, 60, 180, 360, 720, 1440};

    private static final AtomicBoolean running = new AtomicBoolean();

    private V2RaySubscriptions() {
    }

    public static final class Subscription {
        public String id = UUID.randomUUID().toString();
        public String name = "";
        public String url = "";
        public int maxConfigs = 50;
        /** 0: manual only. */
        public int intervalMinutes = 0;
        public long lastUpdate;
        public String lastError;
        public int lastAdded;
        public int lastCandidates;
        /** Dedup keys of the configs this subscription added. */
        public final LinkedHashSet<String> keys = new LinkedHashSet<>();

        public String getDisplayName() {
            if (!TextUtils.isEmpty(name)) {
                return name;
            }
            final String host = Uri.parse(url).getHost();
            return TextUtils.isEmpty(host) ? url : host;
        }

        JSONObject toJson() throws Exception {
            final JSONObject o = new JSONObject();
            o.put("id", id).put("name", name).put("url", url).put("max", maxConfigs).put("interval", intervalMinutes)
                    .put("lastUpdate", lastUpdate).put("lastAdded", lastAdded).put("lastCandidates", lastCandidates);
            if (lastError != null) {
                o.put("lastError", lastError);
            }
            o.put("keys", new JSONArray(keys));
            return o;
        }

        static Subscription fromJson(JSONObject o) {
            final Subscription s = new Subscription();
            s.id = o.optString("id", s.id);
            s.name = o.optString("name");
            s.url = o.optString("url");
            s.maxConfigs = o.optInt("max", 50);
            s.intervalMinutes = o.optInt("interval", 0);
            s.lastUpdate = o.optLong("lastUpdate");
            s.lastAdded = o.optInt("lastAdded");
            s.lastCandidates = o.optInt("lastCandidates");
            s.lastError = o.has("lastError") ? o.optString("lastError") : null;
            final JSONArray keys = o.optJSONArray("keys");
            if (keys != null) {
                for (int i = 0; i < keys.length(); i++) {
                    s.keys.add(keys.optString(i));
                }
            }
            return s;
        }
    }

    public static final class Result {
        public int links;
        public int invalid;
        public int duplicates;
        public int tested;
        public int working;
        public int added;
        public int renamed;
        public boolean limitReached;
        public boolean cancelled;
        public String error;
    }

    public interface Progress {
        /** Called from a background thread. */
        void onProgress(int tested, int total, int working);

        boolean isCancelled();
    }

    // region storage

    public static synchronized List<Subscription> getAll() {
        final ArrayList<Subscription> list = new ArrayList<>();
        try {
            final JSONArray array = new JSONArray(prefs().getString(PREF, "[]"));
            for (int i = 0; i < array.length(); i++) {
                list.add(Subscription.fromJson(array.getJSONObject(i)));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return list;
    }

    public static synchronized Subscription get(String id) {
        for (Subscription s : getAll()) {
            if (s.id.equals(id)) {
                return s;
            }
        }
        return null;
    }

    /** Adds or replaces (by id) a subscription. */
    public static synchronized void save(Subscription subscription) {
        final List<Subscription> all = getAll();
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(subscription.id)) {
                all.set(i, subscription);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            all.add(subscription);
        }
        write(all);
    }

    public static synchronized void remove(String id) {
        final List<Subscription> all = getAll();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(id)) {
                all.remove(i);
                break;
            }
        }
        write(all);
    }

    private static void write(List<Subscription> all) {
        try {
            final JSONArray array = new JSONArray();
            for (Subscription s : all) {
                array.put(s.toJson());
            }
            prefs().edit().putString(PREF, array.toString()).commit();
        } catch (Exception e) {
            FileLog.e(e);
        }
        scheduleJob();
    }

    private static SharedPreferences prefs() {
        return MessagesController.getGlobalMainSettings();
    }

    public static boolean isValidUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        final Uri uri = Uri.parse(url.trim());
        return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) && !TextUtils.isEmpty(uri.getHost());
    }

    // endregion

    // region scheduling

    /**
     * Schedules (or cancels) the periodic background update. JobScheduler's minimum period is
     * 15 minutes, and Android may run jobs later than asked in Doze or for rarely used apps.
     */
    public static void scheduleJob() {
        try {
            final Context context = ApplicationLoader.applicationContext;
            final JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            int minInterval = 0;
            for (Subscription s : getAll()) {
                if (s.intervalMinutes > 0 && (minInterval == 0 || s.intervalMinutes < minInterval)) {
                    minInterval = s.intervalMinutes;
                }
            }
            if (minInterval == 0) {
                scheduler.cancel(JOB_ID);
                return;
            }
            final long period = Math.max(15, minInterval) * 60_000L;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                final JobInfo existing = scheduler.getPendingJob(JOB_ID);
                if (existing != null && existing.getIntervalMillis() == period) {
                    return;
                }
            }
            final JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, V2RaySubscriptionJob.class))
                    .setPeriodic(period)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build();
            scheduler.schedule(job);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Updates the subscriptions whose interval has passed. Runs on the calling (background) thread. */
    static void updateDue(Progress progress, long timeBudgetMs) {
        final long deadline = SystemClock.elapsedRealtime() + timeBudgetMs;
        for (Subscription s : getAll()) {
            if (progress != null && progress.isCancelled() || SystemClock.elapsedRealtime() > deadline) {
                return;
            }
            if (s.intervalMinutes <= 0) {
                continue;
            }
            // A little slack, so a job that fires slightly early still updates.
            if (System.currentTimeMillis() - s.lastUpdate < s.intervalMinutes * 60_000L - 120_000L) {
                continue;
            }
            update(s.id, progress, deadline - SystemClock.elapsedRealtime());
        }
    }

    // endregion

    // region update

    /**
     * Downloads, deduplicates, tests and saves the configs of one subscription. Blocking; call
     * from a background thread. Only one update runs at a time.
     */
    @NonNull
    public static Result update(String subscriptionId, @Nullable Progress progress, long timeBudgetMs) {
        final Result result = new Result();
        if (!running.compareAndSet(false, true)) {
            result.error = "busy";
            return result;
        }
        try {
            updateInternal(subscriptionId, progress, timeBudgetMs, result);
        } catch (Throwable e) {
            FileLog.e(e);
            result.error = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
        } finally {
            running.set(false);
        }
        final Subscription s = get(subscriptionId);
        if (s != null) {
            if (result.error == null) {
                s.lastUpdate = System.currentTimeMillis();
                s.lastAdded = result.added;
                s.lastCandidates = result.links;
            }
            s.lastError = result.error;
            save(s);
        }
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged));
        return result;
    }

    public static boolean isRunning() {
        return running.get();
    }

    private static void updateInternal(String subscriptionId, Progress progress, long timeBudgetMs, Result result) throws Exception {
        final long deadline = SystemClock.elapsedRealtime() + timeBudgetMs;
        final Subscription subscription = get(subscriptionId);
        if (subscription == null) {
            result.error = "not found";
            return;
        }
        final String body;
        try {
            body = download(subscription.url);
        } catch (IOException e) {
            result.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return;
        }
        final List<String> links = parseLinks(body);
        result.links = links.size();
        if (links.isEmpty()) {
            result.error = "no V2Ray links in the subscription";
            return;
        }

        // What is saved already, by dedup key.
        final HashMap<String, SharedConfig.ProxyInfo> saved = new HashMap<>();
        final SharedConfig.ProxyInfo[] current = new SharedConfig.ProxyInfo[1];
        runOnUiAndWait(() -> {
            SharedConfig.loadProxyList();
            current[0] = SharedConfig.currentProxy;
            for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
                if (info.settings.getType() == ProxySettings.Type.V2RAY) {
                    final V2RayConfig config = V2RayConfig.parse(info.settings.getSecret());
                    if (config != null) {
                        saved.put(config.getDedupKey(), info);
                    }
                }
            }
        });

        final ArrayList<V2RayConfig> candidates = new ArrayList<>();
        final HashMap<SharedConfig.ProxyInfo, V2RayConfig> renames = new HashMap<>();
        final HashSet<String> seen = new HashSet<>();
        for (String link : links) {
            final V2RayConfig config = V2RayConfig.parse(link);
            if (config == null) {
                result.invalid++;
                continue;
            }
            final String key = config.getDedupKey();
            if (!seen.add(key)) {
                result.duplicates++;
                continue;
            }
            final SharedConfig.ProxyInfo existing = saved.get(key);
            if (existing != null) {
                result.duplicates++;
                // The subscription renamed one of its own configs: follow it, except for the
                // config in use (its settings identify the active proxy) and ones added by hand.
                if (subscription.keys.contains(key) && existing != current[0] && !link.equals(existing.settings.getSecret())) {
                    renames.put(existing, config);
                }
                continue;
            }
            candidates.add(config);
        }

        int alive = 0;
        for (String key : subscription.keys) {
            if (saved.containsKey(key)) {
                alive++;
            }
        }
        final int slots = Math.max(0, subscription.maxConfigs - alive);
        final ArrayList<V2RayConfig> working = new ArrayList<>();
        final ArrayList<Long> pings = new ArrayList<>();
        if (slots > 0 && !candidates.isEmpty()) {
            testCandidates(candidates, slots, progress, deadline, result, working, pings);
        } else {
            result.limitReached = slots == 0 && !candidates.isEmpty();
        }

        runOnUiAndWait(() -> {
            final Subscription fresh = get(subscriptionId);
            for (Map.Entry<SharedConfig.ProxyInfo, V2RayConfig> rename : renames.entrySet()) {
                final ProxySettings settings = ProxySettings.fromV2RayLink(rename.getValue().link);
                if (settings != null && SharedConfig.proxyList.contains(rename.getKey()) && rename.getKey() != SharedConfig.currentProxy) {
                    rename.getKey().settings = settings;
                    result.renamed++;
                }
            }
            for (int i = 0; i < working.size(); i++) {
                final V2RayConfig config = working.get(i);
                final ProxySettings settings = ProxySettings.fromV2RayLink(config.link);
                if (settings == null) {
                    continue;
                }
                final SharedConfig.ProxyInfo info = new SharedConfig.ProxyInfo(settings);
                info.ping = pings.get(i);
                info.available = true;
                info.availableCheckTime = SystemClock.elapsedRealtime();
                if (SharedConfig.addProxy(info) == info) {
                    result.added++;
                }
                if (fresh != null) {
                    fresh.keys.add(config.getDedupKey());
                }
            }
            if (fresh != null) {
                save(fresh);
            }
            SharedConfig.saveProxyList();
        });
    }

    /**
     * Tests candidates in order until {@code slots} work. A quick TCP connect weeds out dead
     * servers; the verdict comes from a real request through the Xray core.
     */
    private static void testCandidates(List<V2RayConfig> candidates, int slots, Progress progress, long deadline,
                                       Result result, List<V2RayConfig> working, List<Long> pings) throws InterruptedException {
        final ExecutorService tcpPool = Executors.newFixedThreadPool(TCP_THREADS);
        final ExecutorService testPool = Executors.newFixedThreadPool(TEST_THREADS);
        final AtomicInteger tested = new AtomicInteger();
        final AtomicInteger found = new AtomicInteger();
        final Object resultLock = new Object();
        final int total = candidates.size();
        try {
            final int batch = Math.max(TEST_THREADS * 2, 16);
            for (int start = 0; start < candidates.size(); start += batch) {
                if (found.get() >= slots || progress != null && progress.isCancelled() || SystemClock.elapsedRealtime() > deadline) {
                    break;
                }
                final List<V2RayConfig> chunk = candidates.subList(start, Math.min(candidates.size(), start + batch));
                final ArrayList<Future<?>> futures = new ArrayList<>();
                for (V2RayConfig config : chunk) {
                    futures.add(testPool.submit(() -> {
                        if (found.get() >= slots || progress != null && progress.isCancelled() || SystemClock.elapsedRealtime() > deadline) {
                            return;
                        }
                        long delay = -1;
                        if (tcpReachable(tcpPool, config)) {
                            delay = V2RayTransport.measureDelaySync(config);
                        }
                        synchronized (resultLock) {
                            result.tested++;
                            if (delay > 0 && found.get() < slots) {
                                found.incrementAndGet();
                                working.add(config);
                                pings.add(delay);
                                result.working++;
                            }
                        }
                        if (progress != null) {
                            progress.onProgress(tested.incrementAndGet(), total, found.get());
                        }
                    }));
                }
                for (Future<?> future : futures) {
                    try {
                        future.get();
                    } catch (Exception ignore) {
                    }
                }
            }
            result.limitReached = found.get() >= slots;
            result.cancelled = progress != null && progress.isCancelled();
        } finally {
            tcpPool.shutdownNow();
            testPool.shutdownNow();
        }
    }

    private static boolean tcpReachable(ExecutorService pool, V2RayConfig config) {
        try {
            return pool.submit(() -> {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(config.address, config.port), TCP_TIMEOUT_MS);
                    return true;
                } catch (IOException e) {
                    return false;
                }
            }).get(TCP_TIMEOUT_MS + 2000, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    // endregion

    // region download and parsing

    /**
     * Downloads the subscription directly, and through the running V2Ray core when the direct
     * request fails (subscription hosts such as GitHub are often blocked without it).
     */
    private static String download(String url) throws IOException {
        url = toRawUrl(url.trim());
        IOException directError;
        try {
            return download(url, Proxy.NO_PROXY);
        } catch (IOException e) {
            directError = e;
        }
        final int port = V2RayTransport.getRunningPort();
        if (port != 0) {
            try {
                return download(url, new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", port)));
            } catch (IOException ignore) {
            }
        }
        throw directError;
    }

    /** A GitHub "blob" page link serves HTML; the file itself is on raw.githubusercontent.com. */
    static String toRawUrl(String url) {
        final Uri uri = Uri.parse(url);
        if ("github.com".equalsIgnoreCase(uri.getHost()) && uri.getPath() != null && uri.getPath().contains("/blob/")) {
            return "https://raw.githubusercontent.com" + uri.getPath().replaceFirst("/blob/", "/");
        }
        return url;
    }

    private static String download(String url, Proxy proxy) throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) new URL(url.trim()).openConnection(proxy);
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            // Panels (Marzban, 3x-ui...) pick the response format from the client name.
            connection.setRequestProperty("User-Agent", "v2rayNG/1.9.16");
            connection.setRequestProperty("Accept", "*/*");
            final int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = connection.getInputStream()) {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                final byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    if (out.size() > MAX_DOWNLOAD_BYTES) {
                        throw new IOException("subscription is larger than 8 MB");
                    }
                }
                return out.toString("UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }

    /** Share links in a subscription body: plain lines, or the whole body (or each line) in base64. */
    static List<String> parseLinks(String body) {
        final LinkedHashSet<String> links = new LinkedHashSet<>();
        if (TextUtils.isEmpty(body)) {
            return new ArrayList<>();
        }
        collect(body, links);
        if (links.isEmpty()) {
            final String decoded = decodeBase64(body.trim());
            if (decoded != null) {
                collect(decoded, links);
            }
        }
        if (links.isEmpty()) {
            for (String line : body.split("\\s+")) {
                final String decoded = decodeBase64(line);
                if (decoded != null) {
                    collect(decoded, links);
                }
            }
        }
        final ArrayList<String> list = new ArrayList<>(links);
        return list.size() > MAX_LINKS ? new ArrayList<>(list.subList(0, MAX_LINKS)) : list;
    }

    private static void collect(String text, LinkedHashSet<String> links) {
        final java.util.regex.Matcher matcher = V2RayConfig.LINK_PATTERN.matcher(text);
        while (matcher.find()) {
            links.add(V2RayConfig.trimLink(matcher.group()));
        }
    }

    private static String decodeBase64(String value) {
        if (TextUtils.isEmpty(value) || value.length() < 8) {
            return null;
        }
        try {
            final String normalized = value.replaceAll("\\s", "").replace('-', '+').replace('_', '/');
            final byte[] bytes = Base64.decode(normalized, Base64.DEFAULT);
            final String decoded = new String(bytes, StandardCharsets.UTF_8);
            return decoded.contains("://") ? decoded : null;
        } catch (Exception e) {
            return null;
        }
    }

    // endregion

    private static void runOnUiAndWait(Runnable runnable) throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        AndroidUtilities.runOnUIThread(() -> {
            try {
                runnable.run();
            } finally {
                latch.countDown();
            }
        });
        latch.await();
    }
}
