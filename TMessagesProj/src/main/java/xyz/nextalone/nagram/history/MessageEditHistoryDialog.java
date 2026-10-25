package xyz.nextalone.nagram.history;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.List;

import tw.nekomimi.nekogram.utils.AlertUtil;

/**
 * Shows the history of one message: every captured version, oldest first, each with the time it
 * became current and the words added or removed compared with the version before it, plus when
 * the message was deleted if it was.
 */
public final class MessageEditHistoryDialog {

    /** Word diffs are quadratic; longer texts are shown without highlighting. */
    private static final int MAX_DIFF_TOKENS = 600;

    private MessageEditHistoryDialog() {
    }

    public static void show(BaseFragment fragment, MessageObject message) {
        if (fragment == null || fragment.getParentActivity() == null || message == null) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        final long dialogId = message.getDialogId();
        final int messageId = message.getId();
        final Context context = fragment.getParentActivity();
        final List<MessageVersionItem> versions = MessageHistoryController.getVersions(account, dialogId, messageId);
        final DeletedMessageItem deleted = MessageHistoryController.getDeleted(account, dialogId, messageId);
        if (versions.isEmpty() && deleted == null) {
            AlertUtil.showToast(LocaleController.getString(R.string.MessageHistoryEmpty));
            return;
        }

        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(4), AndroidUtilities.dp(18), AndroidUtilities.dp(8));

        if (deleted != null) {
            final TextView banner = makeText(context, 14, Theme.getColor(Theme.key_text_RedBold), true);
            banner.setText(LocaleController.formatString(R.string.MessageHistoryDeletedOn, formatTime(deleted.observedAt / 1000)));
            banner.setBackground(card(Theme.multAlpha(Theme.getColor(Theme.key_text_RedBold), .10f)));
            banner.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
            content.addView(banner, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 4));
        }

        final ArrayList<String> texts = new ArrayList<>();
        if (versions.isEmpty()) {
            // Deleted without any recorded edit: its last content is the only version.
            texts.add(body(deleted.text, deleted.mediaType));
            addVersion(context, content, LocaleController.getString(R.string.MessageHistoryOriginal),
                    formatTime(deleted.originalDate), null, texts.get(0), false);
        } else {
            for (int i = 0; i < versions.size(); i++) {
                final MessageVersionItem v = versions.get(i);
                texts.add(body(v.text, v.mediaType));
                final String title;
                final long time;
                if (i == 0) {
                    title = LocaleController.getString(R.string.MessageHistoryOriginal);
                    time = v.editDate != 0 ? v.editDate : message.messageOwner.date;
                } else {
                    title = LocaleController.formatString(R.string.MessageHistoryEditNumber, i);
                    time = v.editDate != 0 ? v.editDate : v.observedAt / 1000;
                }
                final boolean current = i == versions.size() - 1 && deleted == null;
                addVersion(context, content, title, formatTime(time), i > 0 ? texts.get(i - 1) : null, texts.get(i), current);
            }
        }

        final ScrollView scroll = new ScrollView(context);
        scroll.addView(content);

        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.MessageHistoryViewEdits));
        builder.setView(scroll);
        builder.setPositiveButton(LocaleController.getString(R.string.Close), null);
        builder.setNeutralButton(LocaleController.getString(R.string.MessageHistoryCopyOriginal), (dialog, which) -> {
            AndroidUtilities.addToClipboard(texts.get(0));
            AlertUtil.showToast(LocaleController.getString(R.string.TextCopied));
        });
        fragment.showDialog(builder.create());
    }

    private static void addVersion(Context context, LinearLayout parent, String title, String time, String previous, String text, boolean current) {
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(card(Theme.getColor(Theme.key_dialogBackgroundGray)));
        card.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(10));

        final TextView header = makeText(context, 13, Theme.getColor(Theme.key_dialogTextBlue), true);
        header.setText(title + "  ·  " + time + (current ? "  ·  " + LocaleController.getString(R.string.MessageHistoryCurrent) : ""));
        card.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

        final TextView body = makeText(context, 15, Theme.getColor(Theme.key_dialogTextBlack), false);
        body.setTextIsSelectable(true);
        body.setText(previous == null ? text : diff(previous, text));
        card.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        parent.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
    }

    private static TextView makeText(Context context, int sizeDp, int color, boolean bold) {
        final TextView textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        textView.setTextColor(color);
        textView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        if (bold) {
            textView.setTypeface(AndroidUtilities.bold());
        }
        return textView;
    }

    private static GradientDrawable card(int color) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(AndroidUtilities.dp(10));
        return drawable;
    }

    private static String formatTime(long seconds) {
        if (seconds <= 0) {
            return "—";
        }
        return LocaleController.formatDateTime(seconds, true);
    }

    private static String body(String text, String mediaType) {
        final StringBuilder sb = new StringBuilder();
        final String mediaLabel = mediaLabel(mediaType);
        if (!TextUtils.isEmpty(mediaLabel)) {
            sb.append("[").append(mediaLabel).append("]");
            if (!TextUtils.isEmpty(text)) sb.append("\n");
        }
        if (!TextUtils.isEmpty(text)) sb.append(text);
        return sb.toString();
    }

    /** {@code text} with words added since {@code previous} highlighted and removed ones struck out. */
    static CharSequence diff(String previous, String text) {
        final List<String> a = tokenize(previous);
        final List<String> b = tokenize(text);
        if (a.size() > MAX_DIFF_TOKENS || b.size() > MAX_DIFF_TOKENS) {
            return text;
        }
        final int[][] lcs = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        final int added = Theme.getColor(Theme.key_windowBackgroundWhiteGreenText);
        final int removed = Theme.getColor(Theme.key_text_RedRegular);
        final SpannableStringBuilder out = new SpannableStringBuilder();
        int i = 0, j = 0;
        while (i < a.size() || j < b.size()) {
            if (i < a.size() && j < b.size() && a.get(i).equals(b.get(j))) {
                out.append(b.get(j));
                i++;
                j++;
            } else if (j < b.size() && (i == a.size() || lcs[i][j + 1] >= lcs[i + 1][j])) {
                final int start = out.length();
                out.append(b.get(j++));
                out.setSpan(new ForegroundColorSpan(added), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(Theme.multAlpha(added, .15f)), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                final int start = out.length();
                out.append(a.get(i++));
                out.setSpan(new ForegroundColorSpan(removed), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new StrikethroughSpan(), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return out;
    }

    /** Words and the whitespace after them, so joining the tokens gives back the text. */
    private static List<String> tokenize(String text) {
        final ArrayList<String> tokens = new ArrayList<>();
        if (TextUtils.isEmpty(text)) {
            return tokens;
        }
        int start = 0;
        for (int i = 1; i <= text.length(); i++) {
            if (i == text.length() || Character.isWhitespace(text.charAt(i - 1)) && !Character.isWhitespace(text.charAt(i))) {
                tokens.add(text.substring(start, i));
                start = i;
            }
        }
        return tokens;
    }

    private static String mediaLabel(String mediaType) {
        if (TextUtils.isEmpty(mediaType)) return "";
        switch (mediaType) {
            case "photo": return LocaleController.getString(R.string.AttachPhoto);
            case "video": return LocaleController.getString(R.string.AttachVideo);
            case "voice": return LocaleController.getString(R.string.AttachAudio);
            case "audio": return LocaleController.getString(R.string.AttachMusic);
            case "round": return LocaleController.getString(R.string.AttachRound);
            case "gif": return LocaleController.getString(R.string.AttachGif);
            case "sticker": return LocaleController.getString(R.string.AttachSticker);
            case "document": return LocaleController.getString(R.string.AttachDocument);
            default: return mediaType;
        }
    }
}
