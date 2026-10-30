package tw.nekomimi.nekogram.utils;

import android.text.TextUtils;

/**
 * Text matching for settings search that ignores the differences Persian (and Arabic) text
 * usually has: آ/أ/إ and ا, ي/ى and ی, ك and ک, ة/ۀ and ه, diacritics, tatweel, zero-width
 * joiners/non-joiners, letter case, and Persian/Arabic versus Latin digits. Spaces and
 * half-spaces are ignored too, so "به روز" and "به‌روز" both find "به‌روزرسانی".
 */
public final class PersianSearch {

    private PersianSearch() {
    }

    private static char fold(char c) {
        switch (c) {
            case 'آ':
            case 'أ':
            case 'إ':
            case 'ٱ':
                return 'ا';
            case 'ي':
            case 'ى':
            case 'ئ':
                return 'ی';
            case 'ك':
                return 'ک';
            case 'ة':
            case 'ۀ':
                return 'ه';
            case 'ؤ':
                return 'و';
            case 'ـ': // tatweel
            case '‌': // zero-width non-joiner (half-space)
            case '‍':
            case '‎':
            case '‏':
            case ' ':
            case ' ':
                return 0;
        }
        if (c >= 'ً' && c <= 'ٟ' || c == 'ٰ') {
            return 0; // diacritics
        }
        if (c >= '۰' && c <= '۹') {
            return (char) ('0' + (c - '۰'));
        }
        if (c >= '٠' && c <= '٩') {
            return (char) ('0' + (c - '٠'));
        }
        return Character.toLowerCase(c);
    }

    /** {@code text} folded, with spaces and half-spaces dropped. */
    public static String normalize(CharSequence text) {
        if (text == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            final char c = fold(text.charAt(i));
            if (c != 0) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Where {@code query} occurs in {@code text}, as {start, end} indexes into the original
     * {@code text} (for highlighting), or null.
     */
    public static int[] find(CharSequence text, CharSequence query) {
        if (TextUtils.isEmpty(text) || TextUtils.isEmpty(query)) {
            return null;
        }
        final String needle = normalize(query);
        if (needle.isEmpty()) {
            return null;
        }
        final StringBuilder sb = new StringBuilder(text.length());
        final int[] origin = new int[text.length()];
        for (int i = 0; i < text.length(); i++) {
            final char c = fold(text.charAt(i));
            if (c != 0) {
                origin[sb.length()] = i;
                sb.append(c);
            }
        }
        final int index = sb.indexOf(needle);
        if (index < 0) {
            return null;
        }
        return new int[]{origin[index], origin[index + needle.length() - 1] + 1};
    }

    /** Whether every word of {@code query} occurs in one of {@code texts}. */
    public static boolean matchesAll(String query, CharSequence... texts) {
        if (TextUtils.isEmpty(query)) {
            return false;
        }
        final StringBuilder haystack = new StringBuilder();
        for (CharSequence text : texts) {
            haystack.append(normalize(text)).append('\n');
        }
        boolean any = false;
        for (String word : query.trim().split("\\s+")) {
            final String needle = normalize(word);
            if (needle.isEmpty()) {
                continue;
            }
            any = true;
            if (haystack.indexOf(needle) < 0) {
                return false;
            }
        }
        return any;
    }
}
