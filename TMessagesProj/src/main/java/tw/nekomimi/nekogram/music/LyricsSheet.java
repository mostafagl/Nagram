package tw.nekomimi.nekogram.music;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lyrics for the song that is playing, over a background tinted from its cover. Lyrics come from
 * LRCLIB (lrclib.net, a free public lyrics database): only the song's title, artist and length are
 * sent, and only when the user opens this sheet. Time-synced lyrics follow the song, highlighting
 * the current line; tapping a line seeks there.
 */
public class LyricsSheet extends BottomSheet {

    private static final Pattern LRC_LINE = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]");

    private final MessageObject song;
    private final LinearLayout linesLayout;
    private final ScrollView scrollView;
    private final TextView statusView;
    private final FrameLayout root;
    private final ArrayList<Long> lineTimes = new ArrayList<>();
    private final ArrayList<TextView> lineViews = new ArrayList<>();
    private int currentLine = -1;
    private boolean dismissed;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (dismissed) {
                return;
            }
            updateCurrentLine(true);
            AndroidUtilities.runOnUIThread(this, 250);
        }
    };

    public static void show(Context context, MessageObject song) {
        if (context == null || song == null) {
            return;
        }
        new LyricsSheet(context, song).show();
    }

    private LyricsSheet(Context context, MessageObject song) {
        super(context, false);
        this.song = song;
        fixNavigationBar(0xFF101010);

        root = new FrameLayout(context);
        root.setBackgroundColor(0xFF202020);

        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, (int) (AndroidUtilities.displaySize.y / AndroidUtilities.density * 0.75f)));

        final LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(18), AndroidUtilities.dp(18), AndroidUtilities.dp(10));
        final BackupImageView cover = new BackupImageView(context);
        cover.setRoundRadius(AndroidUtilities.dp(8));
        header.addView(cover, LayoutHelper.createLinear(56, 56));
        final LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        final TextView title = new TextView(context);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        title.setTypeface(AndroidUtilities.bold());
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setText(song.getMusicTitle());
        titles.addView(title);
        final TextView artist = new TextView(context);
        artist.setTextColor(0xB3FFFFFF);
        artist.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        artist.setText(song.getMusicAuthor());
        titles.addView(artist);
        header.addView(titles, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 14, 0, 0, 0));
        column.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        scrollView = new ScrollView(context);
        scrollView.setVerticalScrollBarEnabled(false);
        linesLayout = new LinearLayout(context);
        linesLayout.setOrientation(LinearLayout.VERTICAL);
        linesLayout.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(8), AndroidUtilities.dp(22), AndroidUtilities.dp(120));
        scrollView.addView(linesLayout);
        column.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        statusView = new TextView(context);
        statusView.setTextColor(0xB3FFFFFF);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        statusView.setGravity(Gravity.CENTER);
        statusView.setText(LocaleController.getString(R.string.LyricsLoading));
        root.addView(statusView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 24, 0, 24, 0));

        setCustomView(root);
        loadCover(cover);
        loadLyrics();
    }

    @Override
    public void dismiss() {
        dismissed = true;
        AndroidUtilities.cancelRunOnUIThread(ticker);
        super.dismiss();
    }

    /** Shows the cover and tints the background with its colors. */
    private void loadCover(BackupImageView cover) {
        final TLRPC.Document document = song.getDocument();
        final TLRPC.PhotoSize thumb = document != null ? FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 320) : null;
        final String artwork = song.getArtworkUrl(false);
        cover.getImageReceiver().setDelegate(new ImageReceiver.ImageReceiverDelegate() {
            @Override
            public void didSetImage(ImageReceiver imageReceiver, boolean set, boolean thumb, boolean memCache) {
                final Bitmap bitmap = imageReceiver.getBitmap();
                if (bitmap != null && !bitmap.isRecycled()) {
                    applyBackground(bitmap);
                }
            }
        });
        final ImageLocation thumbLocation = thumb != null ? ImageLocation.getForDocument(thumb, document) : null;
        if (!TextUtils.isEmpty(artwork)) {
            cover.setImage(ImageLocation.getForPath(artwork), "112_112", thumbLocation, null, null, 0, 1, song);
        } else if (thumbLocation != null) {
            cover.setImage(thumbLocation, "112_112", (Drawable) null, song);
        } else {
            cover.setBackground(org.telegram.ui.ActionBar.Theme.createRoundRectDrawable(AndroidUtilities.dp(8), 0x33FFFFFF));
        }
    }

    private void applyBackground(Bitmap bitmap) {
        final int[] colors = AndroidUtilities.calcDrawableColor(new BitmapDrawable(getContext().getResources(), bitmap));
        if (colors == null || colors.length < 2) {
            return;
        }
        final GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{darken(colors[0], 0.55f), darken(colors[1], 0.25f)});
        root.setBackground(gradient);
    }

    private static int darken(int color, float factor) {
        return Color.rgb((int) (Color.red(color) * factor), (int) (Color.green(color) * factor), (int) (Color.blue(color) * factor));
    }

    private void loadLyrics() {
        final String track = song.getMusicTitle();
        final String artistName = song.getMusicAuthor();
        final int duration = (int) Math.round(song.getDuration());
        Utilities.globalQueue.postRunnable(() -> {
            String synced = null;
            String plain = null;
            try {
                JSONObject exact = null;
                if (!TextUtils.isEmpty(track) && !TextUtils.isEmpty(artistName)) {
                    final String json = get("https://lrclib.net/api/get?track_name=" + enc(track) + "&artist_name=" + enc(artistName)
                            + (duration > 0 ? "&duration=" + duration : ""));
                    if (json != null) {
                        exact = new JSONObject(json);
                    }
                }
                if (exact == null || exact.optString("syncedLyrics", "").isEmpty() && exact.optString("plainLyrics", "").isEmpty()) {
                    final String json = get("https://lrclib.net/api/search?q=" + enc((track == null ? "" : track) + " " + (artistName == null ? "" : artistName)));
                    if (json != null) {
                        final JSONArray results = new JSONArray(json);
                        for (int i = 0; i < results.length(); i++) {
                            final JSONObject r = results.getJSONObject(i);
                            if (!r.optString("syncedLyrics", "").isEmpty() || !r.optString("plainLyrics", "").isEmpty()) {
                                exact = r;
                                break;
                            }
                        }
                    }
                }
                if (exact != null) {
                    synced = exact.isNull("syncedLyrics") ? null : exact.optString("syncedLyrics", null);
                    plain = exact.isNull("plainLyrics") ? null : exact.optString("plainLyrics", null);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final String syncedFinal = synced;
            final String plainFinal = plain;
            AndroidUtilities.runOnUIThread(() -> showLyrics(syncedFinal, plainFinal));
        });
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s.trim(), "UTF-8");
    }

    /** The body of a successful GET, or null (404 = no lyrics for this song). */
    private static String get(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", "Parvagram (https://github.com/mostafagl/Nagram)");
            if (connection.getResponseCode() != 200) {
                return null;
            }
            final StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void showLyrics(String synced, String plain) {
        if (dismissed) {
            return;
        }
        linesLayout.removeAllViews();
        lineTimes.clear();
        lineViews.clear();
        if (!TextUtils.isEmpty(synced)) {
            for (String raw : synced.split("\n")) {
                final Matcher m = LRC_LINE.matcher(raw);
                final ArrayList<Long> times = new ArrayList<>();
                int end = 0;
                while (m.find() && m.start() == end) {
                    final long min = Long.parseLong(m.group(1));
                    final long sec = Long.parseLong(m.group(2));
                    long ms = 0;
                    if (m.group(3) != null) {
                        final String frac = m.group(3);
                        ms = Long.parseLong(frac) * (frac.length() == 1 ? 100 : frac.length() == 2 ? 10 : 1);
                    }
                    times.add((min * 60 + sec) * 1000 + ms);
                    end = m.end();
                }
                if (times.isEmpty()) {
                    continue;
                }
                final String text = raw.substring(end).trim();
                for (long t : times) {
                    insertLine(t, text.isEmpty() ? "♪" : text);
                }
            }
        }
        if (lineViews.isEmpty() && !TextUtils.isEmpty(plain)) {
            for (String line : plain.split("\n")) {
                addLineView(line.trim(), -1);
            }
        }
        if (linesLayout.getChildCount() == 0) {
            statusView.setText(LocaleController.getString(R.string.LyricsNotFound));
            statusView.setVisibility(View.VISIBLE);
            return;
        }
        statusView.setVisibility(View.GONE);
        if (!lineTimes.isEmpty()) {
            updateCurrentLine(false);
            AndroidUtilities.runOnUIThread(ticker, 250);
        }
    }

    /** Synced lines in time order (one LRC line can carry several times). */
    private void insertLine(long time, String text) {
        int index = 0;
        while (index < lineTimes.size() && lineTimes.get(index) <= time) {
            index++;
        }
        lineTimes.add(index, time);
        final TextView view = addLineView(text, index);
        lineViews.add(index, view);
    }

    private TextView addLineView(String text, int index) {
        final TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextColor(0x80FFFFFF);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 21);
        view.setTypeface(AndroidUtilities.bold());
        view.setPadding(0, AndroidUtilities.dp(7), 0, AndroidUtilities.dp(7));
        if (index >= 0) {
            view.setOnClickListener(v -> {
                final int i = lineViews.indexOf(view);
                final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
                if (i >= 0 && playing != null && playing.getId() == song.getId() && playing.getDuration() > 0) {
                    MediaController.getInstance().seekToProgress(playing, Math.min(1f, lineTimes.get(i) / 1000f / (float) playing.getDuration()));
                }
            });
            linesLayout.addView(view, index);
        } else {
            view.setTextColor(0xE6FFFFFF);
            linesLayout.addView(view);
        }
        return view;
    }

    private void updateCurrentLine(boolean animated) {
        final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing == null || playing.getId() != song.getId() || playing.getDialogId() != song.getDialogId()) {
            return;
        }
        final long position = (long) (playing.audioProgress * playing.getDuration() * 1000);
        int line = -1;
        for (int i = 0; i < lineTimes.size(); i++) {
            if (lineTimes.get(i) <= position) {
                line = i;
            } else {
                break;
            }
        }
        if (line == currentLine) {
            return;
        }
        if (currentLine >= 0 && currentLine < lineViews.size()) {
            lineViews.get(currentLine).setTextColor(0x80FFFFFF);
        }
        currentLine = line;
        if (line >= 0) {
            final TextView view = lineViews.get(line);
            view.setTextColor(Color.WHITE);
            final int target = Math.max(0, view.getTop() - scrollView.getHeight() / 3);
            if (animated) {
                scrollView.smoothScrollTo(0, target);
            } else {
                scrollView.scrollTo(0, target);
            }
        }
    }
}
