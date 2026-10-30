package tw.nekomimi.nekogram.helpers;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

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
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
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
 * the installed one is offered in the chat list, then downloaded and handed to the system
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
        release.notes = json.optString("body");
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

    /** Asks, downloads with a progress dialog, then opens the system installer. */
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

        final LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(4), AndroidUtilities.dp(24), AndroidUtilities.dp(8));
        final ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setProgressTintList(ColorStateList.valueOf(Theme.getColor(Theme.key_dialogLineProgress)));
        bar.setIndeterminateTintList(ColorStateList.valueOf(Theme.getColor(Theme.key_dialogLineProgress)));
        layout.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 8));
        final TextView status = new TextView(activity);
        status.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        status.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        status.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        status.setText(LocaleController.getString(R.string.ParvaUpdateDownloading));
        layout.addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        cancelDownload = false;
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.ParvaUpdateTitle))
                .setView(layout)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), (d, w) -> cancelDownload = true)
                .create();
        dialog.setCanCancel(false);
        dialog.show();

        new Thread(() -> {
            final File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "media");
            dir.mkdirs();
            final File apk = new File(dir, "Parvagram-update.apk");
            String error = null;
            try (FileOutputStream out = new FileOutputStream(apk)) {
                download(release.apkUrl, out, 300L * 1024 * 1024, (done, total) -> {
                    final long size = total > 0 ? total : release.apkSize;
                    AndroidUtilities.runOnUIThread(() -> {
                        if (size > 0) {
                            bar.setIndeterminate(false);
                            bar.setMax(1000);
                            bar.setProgress((int) (done * 1000 / size));
                            status.setText(AndroidUtilities.formatFileSize(done) + " / " + AndroidUtilities.formatFileSize(size));
                        } else {
                            status.setText(AndroidUtilities.formatFileSize(done));
                        }
                    });
                });
            } catch (Exception e) {
                FileLog.e(e);
                error = e.getMessage();
            }
            final String finalError = error;
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    dialog.dismiss();
                } catch (Exception ignore) {
                }
                if (cancelDownload) {
                    return;
                }
                if (finalError != null) {
                    new AlertDialog.Builder(activity)
                            .setTitle(LocaleController.getString(R.string.ParvaUpdateTitle))
                            .setMessage(LocaleController.formatString(R.string.ParvaUpdateFailed, finalError))
                            .setPositiveButton(LocaleController.getString(R.string.OK), null)
                            .show();
                    return;
                }
                install(activity, apk);
            });
        }, "parva-update-download").start();
    }

    private static void install(Activity activity, File apk) {
        try {
            final Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", apk), "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
