package xyz.nextalone.nagram.helper;

import java.util.Locale;

public class BackupFileHelper {

    public static final String STICKERS_SUFFIX = ".parvagram-stickers.json";
    public static final String SETTINGS_SUFFIX = ".parvagram-settings.json";

    // backups exported by older builds (still importable)
    private static final String[] LEGACY_STICKERS_SUFFIXES = {".nagram-stickers.json", ".nekox-stickers.json"};
    private static final String[] LEGACY_SETTINGS_SUFFIXES = {".nagram-settings.json", ".nekox-settings.json"};

    public static boolean isStickersFile(String name) {
        return endsWith(name, STICKERS_SUFFIX) || endsWithAny(name, LEGACY_STICKERS_SUFFIXES);
    }

    public static boolean isSettingsFile(String name) {
        return endsWith(name, SETTINGS_SUFFIX) || endsWithAny(name, LEGACY_SETTINGS_SUFFIXES);
    }

    private static boolean endsWithAny(String name, String[] suffixes) {
        for (String suffix : suffixes) {
            if (endsWith(name, suffix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean endsWith(String name, String suffix) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(suffix);
    }
}
