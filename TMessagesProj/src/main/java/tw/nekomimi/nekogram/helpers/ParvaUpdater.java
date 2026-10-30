package tw.nekomimi.nekogram.helpers;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.utils.proxy.V2RayTransport;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Updates from the app's GitHub releases, inside the app. Every CI build is published as a
 * pre-release tagged {@code build-<timestamp>}; publishing one as a full release (unticking
 * "pre-release") makes it GitHub's "latest release", which this checks for. A newer build than
 * the installed one is offered in the chat list, then downloaded in the background and handed to the system
 * installer, which installs it over this app (same package and signing key).
 */
public final class ParvaUpdater {

    private static final String PREF_RELEASE = "parva_update_release";
    private static final String PREF_LAST_CHECK = "parva_update_last_check";
    private static final String PREF_DISMISSED = "parva_update_dismissed";
    private static final long CHECK_INTERVAL_MS = 3 * 60 * 60 * 1000L;
    private static final Pattern BUILD_TAG = Pattern.compile("build-(\\d{12,})");

    private static volatile boolean checking;
    private static volatile boolean cancelDownload;

    private ParvaUpdater() {
    }

    public static final class Release {
        public long build;
        public String tag;
        public String name;
        public String notes;
        public String apkUrl;
        public long apkSize;

        JSONObject toJson() throws Exception {
            return new JSONObject().put("build", build).put("tag", tag).put("name", name).put("notes", notes)
                    .put("apkUrl", apkUrl).put("apkSize", apkSize);
        }

        static Release fromJson(JSONObject o) {
            final Release r = new Release();
            r.build = o.optLong("build");
            r.tag = o.optString("tag");
            r.name = o.optString("name");
            r.notes = o.optString("notes");
            r.apkUrl = o.optString("apkUrl");
            r.apkSize = o.optLong("apkSize");
            return r;
        }
    }

    private static SharedPreferences prefs() {
        return MessagesController.getGlobalMainSettings();
    }

    /** The newer release waiting to be installed, unless the user closed its notice. */
    public static Release getAvailable() {
        try {
            final String json = prefs().getString(PREF_RELEASE, null);
            if (json == null) {
                return null;
            }
            final Release r = Release.fromJson(new JSONObject(json));
            if (r.build <= BuildConfig.BUILD_TIMESTAMP || r.build == prefs().getLong(PREF_DISMISSED, 0)) {
                return null;
            }
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    public static void dismiss(Release release) {
        prefs().edit().putLong(PREF_DISMISSED, release.build).apply();
    }

    /** Checks GitHub at most every few hours; posts {@link NotificationCenter#parvaUpdateAvailable}. */
    public static void checkIfNeeded() {
        if (checking || System.currentTimeMillis() - prefs().getLong(PREF_LAST_CHECK, 0) < CHECK_INTERVAL_MS) {
            return;
        }
        checking = true;
        new Thread(() -> {
            try {
                final Release release = fetchLatest();
                prefs().edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply();
                if (release != null && release.build > BuildConfig.BUILD_TIMESTAMP) {
                    prefs().edit().putString(PREF_RELEASE, release.toJson().toString()).apply();
                    AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.parvaUpdateAvailable));
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                checking = false;
            }
        }, "parva-update-check").start();
    }

    /** "Check for updates" in settings: checks now and offers the update, or says there is none. */
    public static void checkNow(Activity activity) {
        if (activity == null) {
            return;
        }
        final AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        progress.show();
        new Thread(() -> {
            Release release = null;
            String error = null;
            try {
                release = fetchLatest();
                prefs().edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply();
                if (release != null && release.build > BuildConfig.BUILD_TIMESTAMP) {
                    prefs().edit().putString(PREF_RELEASE, release.toJson().toString()).remove(PREF_DISMISSED).apply();
                }
            } catch (Throwable e) {
                FileLog.e(e);
                // 404: nothing has been published as a release yet, so there's nothing newer.
                error = e.getMessage() != null && e.getMessage().contains("404") ? null : String.valueOf(e.getMessage());
            }
            final Release found = release;
            final String finalError = error;
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    progress.dismiss();
                } catch (Exception ignore) {
                }
                if (found != null && found.build > BuildConfig.BUILD_TIMESTAMP) {
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.parvaUpdateAvailable);
                    new AlertDialog.Builder(activity)
                            .setTitle(LocaleController.getString(R.string.ParvaUpdateNewTitle))
                            .setMessage(TextUtils.isEmpty(found.notes) ? LocaleController.getString(R.string.ParvaUpdateNoNotes) : found.notes)
                            .setPositiveButton(LocaleController.getString(R.string.ParvaUpdateInstall), (d, w) -> startUpdate(activity, found))
                            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                            .show();
                } else {
                    new AlertDialog.Builder(activity)
                            .setTitle(LocaleController.getString(R.string.ParvaUpdateTitle))
                            .setMessage(finalError != null ? LocaleController.formatString(R.string.ParvaUpdateCheckFailed, finalError) : LocaleController.getString(R.string.ParvaUpdateLatest))
                            .setPositiveButton(LocaleController.getString(R.string.OK), null)
                            .show();
                }
            });
        }, "parva-update-check-now").start();
    }

    /**
     * The "what's new" part of a release's notes: the text between {@code <!-- changes -->} and
     * {@code <!-- /changes -->}, without Markdown marks. The rest of the notes is for the developer
     * (download table, how to publish), so a release without that part shows no notes.
     */
    static String userNotes(String body) {
        if (body == null) {
            return "";
        }
        final int start = body.indexOf("<!-- changes -->");
        final int end = body.indexOf("<!-- /changes -->");
        if (start < 0 || end <= start) {
            return "";
        }
        final StringBuilder out = new StringBuilder();
        for (String line : body.substring(start + "<!-- changes -->".length(), end).split("\n")) {
            String l = line.trim();
            if (l.isEmpty()) {
                if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                    out.append('\n');
                }
                continue;
            }
            if (l.startsWith("- ") || l.startsWith("* ")) {
                l = "• " + l.substring(2);
            }
            l = l.replaceAll("^#+\\s*", "").replace("**", "").replace("`", "");
            out.append(l).append('\n');
        }
        return out.toString().trim();
    }

    private static String repoPath() {
        return Uri.parse(BuildVars.GITHUB_REPO_URL).getPath().replaceFirst("^/", "");
    }

    private static String abi() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi) || "armeabi-v7a".equals(abi) || "x86_64".equals(abi)) {
                return abi;
            }
        }
        return "arm64-v8a";
    }

    private static Release fetchLatest() throws Exception {
        final String body = new String(get("https://api.github.com/repos/" + repoPath() + "/releases/latest", 2 * 1024 * 1024), "UTF-8");
        final JSONObject json = new JSONObject(body);
        if (json.optBoolean("prerelease") || json.optBoolean("draft")) {
            return null;
        }
        final Release release = new Release();
        release.tag = json.optString("tag_name");
        release.name = json.optString("name", release.tag);
        release.notes = userNotes(json.optString("body"));
        final Matcher m = BUILD_TAG.matcher(release.tag);
        if (!m.find()) {
            return null; // not a build published by CI: its version can't be compared
        }
        release.build = Long.parseLong(m.group(1));
        final String abi = abi();
        final JSONArray assets = json.optJSONArray("assets");
        if (assets == null) {
            return null;
        }
        for (int i = 0; i < assets.length(); i++) {
            final JSONObject asset = assets.getJSONObject(i);
            final String name = asset.optString("name");
            if (name.endsWith(".apk") && name.contains(abi)) {
                release.apkUrl = asset.optString("browser_download_url");
                release.apkSize = asset.optLong("size");
                break;
            }
        }
        return TextUtils.isEmpty(release.apkUrl) ? null : release;
    }

    /** GET with a direct connection, then through the running V2Ray core (GitHub is often blocked). */
    private static byte[] get(String url, long maxBytes) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        download(url, out, maxBytes, null);
        return out.toByteArray();
    }

    private interface ProgressListener {
        void onProgress(long done, long total);
    }

    private static void download(String url, OutputStream out, long maxBytes, ProgressListener listener) throws IOException {
        IOException error;
        try {
            download(url, Proxy.NO_PROXY, out, maxBytes, listener);
            return;
        } catch (IOException e) {
            error = e;
        }
        final int port = V2RayTransport.getRunningPort();
        if (port != 0) {
            download(url, new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", port)), out, maxBytes, listener);
            return;
        }
        throw error;
    }

    private static void download(String url, Proxy proxy, OutputStream out, long maxBytes, ProgressListener listener) throws IOException {
        String current = url;
        for (int redirects = 0; redirects < 6; redirects++) {
            final HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection(proxy);
            try {
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "Parvagram-Updater");
                connection.setRequestProperty("Accept", current.contains("api.github.com") ? "application/vnd.github+json" : "*/*");
                final int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    final String location = connection.getHeaderField("Location");
                    if (location == null) {
                        throw new IOException("HTTP " + code);
                    }
                    current = new URL(new URL(current), location).toString();
                    continue;
                }
                if (code < 200 || code >= 300) {
                    throw new IOException("HTTP " + code);
                }
                final long total = connection.getContentLengthLong();
                try (InputStream in = connection.getInputStream()) {
                    final byte[] buffer = new byte[64 * 1024];
                    long done = 0;
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        if (cancelDownload) {
                            throw new IOException("cancelled");
                        }
                        out.write(buffer, 0, read);
                        done += read;
                        if (done > maxBytes) {
                            throw new IOException("too large");
                        }
                        if (listener != null) {
                            listener.onProgress(done, total);
                        }
                    }
                }
                return;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    private static volatile boolean downloading;
    private static volatile long downloadDone, downloadTotal;
    private static volatile long downloadedBuild;
    private static long lastProgressPost;

    private static File apkFile() {
        final File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "media");
        dir.mkdirs();
        return new File(dir, "Parvagram-update.apk");
    }

    public static boolean isDownloading() {
        return downloading;
    }

    /** Whether {@code release} is downloaded and only waits for the installer. */
    public static boolean isDownloaded(Release release) {
        return release != null && downloadedBuild == release.build && apkFile().exists();
    }

    /** "12 MB / 64 MB" for the chat list's update card while downloading. */
    public static String getProgressText() {
        final long done = downloadDone;
        final long total = downloadTotal;
        return total > 0
                ? AndroidUtilities.formatFileSize(done) + " / " + AndroidUtilities.formatFileSize(total) + " (" + (done * 100 / total) + "%)"
                : AndroidUtilities.formatFileSize(done);
    }

    public static void cancelDownload() {
        cancelDownload = true;
    }

    private static void postState() {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.parvaUpdateAvailable));
    }

    /**
     * Downloads in the background (progress shows in the update card at the top of the chat list,
     * so chats stay usable), then opens the system installer.
     */
    public static void startUpdate(Activity activity, Release release) {
        if (activity == null || release == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                    .setTitle(LocaleController.getString(R.string.ParvaUpdateTitle))
                    .setMessage(LocaleController.getString(R.string.ParvaUpdateAllowInstall))
                    .setPositiveButton(LocaleController.getString(R.string.ParvaUpdateOpenSettings), (d, w) -> {
                        try {
                            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName())));
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                    })
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .show();
            return;
        }
        if (isDownloaded(release)) {
            install(apkFile());
            return;
        }
        if (downloading) {
            Toast.makeText(activity, LocaleController.getString(R.string.ParvaUpdateInBackground), Toast.LENGTH_LONG).show();
            return;
        }
        downloading = true;
        cancelDownload = false;
        downloadDone = 0;
        downloadTotal = release.apkSize;
        downloadedBuild = 0;
        postState();
        Toast.makeText(activity, LocaleController.getString(R.string.ParvaUpdateInBackground), Toast.LENGTH_LONG).show();

        new Thread(() -> {
            final File apk = apkFile();
            String error = null;
            try (FileOutputStream out = new FileOutputStream(apk)) {
                download(release.apkUrl, out, 300L * 1024 * 1024, (done, total) -> {
                    downloadDone = done;
                    if (total > 0) {
                        downloadTotal = total;
                    }
                    final long now = System.currentTimeMillis();
                    if (now - lastProgressPost > 500) {
                        lastProgressPost = now;
                        postState();
                    }
                });
            } catch (Exception e) {
                FileLog.e(e);
                error = e.getMessage();
            }
            final boolean cancelled = cancelDownload;
            if (error == null && !cancelled) {
                downloadedBuild = release.build;
            } else {
                apk.delete();
            }
            downloading = false;
            final String finalError = error;
            AndroidUtilities.runOnUIThread(() -> {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.parvaUpdateAvailable);
                if (cancelled) {
                    return;
                }
                if (finalError != null) {
                    Toast.makeText(ApplicationLoader.applicationContext, LocaleController.formatString(R.string.ParvaUpdateFailed, finalError), Toast.LENGTH_LONG).show();
                    return;
                }
                install(apk);
            });
        }, "parva-update-download").start();
    }

    private static void install(File apk) {
        try {
            final Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(FileProvider.getUriForFile(ApplicationLoader.applicationContext, ApplicationLoader.getApplicationId() + ".provider", apk), "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            ApplicationLoader.applicationContext.startActivity(intent);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
