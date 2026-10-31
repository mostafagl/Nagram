package xyz.nextalone.nagram.font;

import android.content.Context;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.text.TextPaint;
import android.text.TextUtils;
import android.widget.TextView;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The app font the user picked: the system font, the bundled Vazirmatn, or a TTF/OTF file they
 * imported (copied into the app's files so it keeps working if the original is deleted).
 * Applied to message and chat list text, names and titles and other bold text (through
 * {@link org.telegram.messenger.AndroidUtilities#bold()}), the message input and the common
 * settings rows.
 */
public final class CustomFonts {

    private static final String PREF = "parva_custom_font";
    public static final String SYSTEM = "";
    public static final String VAZIRMATN = "builtin:vazirmatn";
    private static final String VAZIRMATN_REGULAR = "fonts/vazirmatn/Vazirmatn-Regular.ttf";
    private static final String VAZIRMATN_MEDIUM = "fonts/vazirmatn/Vazirmatn-Medium.ttf";
    private static final long MAX_FONT_SIZE = 30L * 1024 * 1024;

    private static boolean loaded;
    private static Typeface regular;
    private static Typeface medium;

    private CustomFonts() {
    }

    public static final class FontItem {
        public final String id;
        public final String name;

        FontItem(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public static String getSelected() {
        return MessagesController.getGlobalMainSettings().getString(PREF, SYSTEM);
    }

    /** Saves the choice; text drawn from now on uses it, already created views after a restart. */
    public static void setSelected(String id) {
        MessagesController.getGlobalMainSettings().edit().putString(PREF, id == null ? SYSTEM : id).commit();
        synchronized (CustomFonts.class) {
            loaded = false;
            regular = null;
            medium = null;
        }
    }

    public static boolean isCustom() {
        ensureLoaded();
        return regular != null;
    }

    /** The regular face of the chosen font, or null for the system font. */
    public static Typeface getRegular() {
        ensureLoaded();
        return regular;
    }

    /** The medium/bold face of the chosen font, or null for the system font. */
    public static Typeface getMedium() {
        ensureLoaded();
        return medium;
    }

    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        final String id = getSelected();
        if (TextUtils.isEmpty(id)) {
            return;
        }
        final Typeface[] faces = load(id);
        if (faces == null) {
            // The file went away or broke: fall back to the system font instead of failing.
            return;
        }
        regular = faces[0];
        medium = faces[1];
    }

    /** Regular and medium faces for a font id, or null if it can't be loaded. Used for previews. */
    public static Typeface[] load(String id) {
        try {
            if (VAZIRMATN.equals(id)) {
                final Context context = ApplicationLoader.applicationContext;
                return new Typeface[]{
                        Typeface.createFromAsset(context.getAssets(), VAZIRMATN_REGULAR),
                        Typeface.createFromAsset(context.getAssets(), VAZIRMATN_MEDIUM)
                };
            }
            final File file = new File(id);
            if (!file.exists()) {
                return null;
            }
            final Typeface base = Typeface.createFromFile(file);
            final Typeface bold;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                bold = Typeface.create(base, 600, false);
            } else {
                bold = Typeface.create(base, Typeface.BOLD);
            }
            return new Typeface[]{base, bold};
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    public static void apply(TextPaint... paints) {
        final Typeface typeface = getRegular();
        if (typeface == null) {
            return;
        }
        for (TextPaint paint : paints) {
            if (paint != null) {
                paint.setTypeface(typeface);
            }
        }
    }

    /** Applies the regular face to a view that uses the default typeface; bold views keep theirs. */
    public static void apply(TextView view) {
        final Typeface typeface = getRegular();
        if (typeface == null || view == null) {
            return;
        }
        final Typeface current = view.getTypeface();
        if (current == null || current == Typeface.DEFAULT || current == Typeface.SANS_SERIF) {
            view.setTypeface(typeface);
        }
    }

    /** Applies the regular face to a SimpleTextView (status lines such as "last seen recently"). */
    public static void apply(org.telegram.ui.ActionBar.SimpleTextView view) {
        final Typeface typeface = getRegular();
        if (typeface != null && view != null) {
            view.setTypeface(typeface);
        }
    }

    public static void apply(org.telegram.ui.Components.AnimatedTextView view) {
        final Typeface typeface = getRegular();
        if (typeface != null && view != null) {
            view.setTypeface(typeface);
        }
    }

    private static File fontsDir() {
        final File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "custom_fonts");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    public static List<FontItem> list(String systemName, String vazirName) {
        final ArrayList<FontItem> items = new ArrayList<>();
        items.add(new FontItem(SYSTEM, systemName));
        items.add(new FontItem(VAZIRMATN, vazirName));
        final File[] files = fontsDir().listFiles();
        if (files != null) {
            Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File file : files) {
                final String name = file.getName();
                final int dot = name.lastIndexOf('.');
                items.add(new FontItem(file.getAbsolutePath(), dot > 0 ? name.substring(0, dot) : name));
            }
        }
        return items;
    }

    public static boolean isImported(String id) {
        return !TextUtils.isEmpty(id) && !VAZIRMATN.equals(id);
    }

    public static void delete(String id) {
        if (!isImported(id)) {
            return;
        }
        if (id.equals(getSelected())) {
            setSelected(SYSTEM);
        }
        //noinspection ResultOfMethodCallIgnored
        new File(id).delete();
    }

    /**
     * Copies a picked TTF/OTF into the app and checks that Android can load it.
     *
     * @return the new font's id
     * @throws Exception with a short reason when the file is not a usable font
     */
    public static String importFont(Context context, Uri uri) throws Exception {
        String name = null;
        try (android.database.Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                name = cursor.getString(0);
            }
        } catch (Throwable ignore) {
        }
        if (TextUtils.isEmpty(name)) {
            name = "font.ttf";
        }
        name = name.replaceAll("[^\\p{L}\\p{N}._ -]", "_");
        final String lower = name.toLowerCase(Locale.US);
        if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) {
            name = name + ".ttf";
        }
        File target = new File(fontsDir(), name);
        for (int i = 2; target.exists(); i++) {
            final int dot = name.lastIndexOf('.');
            target = new File(fontsDir(), name.substring(0, dot) + " (" + i + ")" + name.substring(dot));
        }
        final File temp = new File(fontsDir(), ".import.tmp");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(temp)) {
            if (in == null) {
                throw new IllegalArgumentException("cannot read file");
            }
            final byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > MAX_FONT_SIZE) {
                    throw new IllegalArgumentException("file is too large");
                }
                out.write(buffer, 0, read);
            }
        }
        try {
            if (!hasFontSignature(temp)) {
                throw new IllegalArgumentException("not a TTF/OTF font");
            }
            // Throws when the font can't be loaded.
            Typeface.createFromFile(temp);
            if (!temp.renameTo(target)) {
                throw new IllegalStateException("cannot save font");
            }
            return target.getAbsolutePath();
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private static boolean hasFontSignature(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            final byte[] header = new byte[4];
            if (in.read(header) != 4) {
                return false;
            }
            final String tag = new String(header, "ISO-8859-1");
            return (header[0] == 0 && header[1] == 1 && header[2] == 0 && header[3] == 0)
                    || "OTTO".equals(tag) || "true".equals(tag) || "ttcf".equals(tag);
        } catch (Exception e) {
            return false;
        }
    }
}
