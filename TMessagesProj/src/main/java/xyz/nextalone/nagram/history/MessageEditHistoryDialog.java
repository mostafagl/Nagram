package xyz.nextalone.nagram.history;

import android.content.Context;
import android.text.TextUtils;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.List;

import tw.nekomimi.nekogram.utils.AlertUtil;

/**
 * Shows every captured version of a single message, oldest first.
 */
public final class MessageEditHistoryDialog {

    private MessageEditHistoryDialog() {
    }

    public static void show(BaseFragment fragment, long dialogId, long messageId, String title) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final Context context = fragment.getParentActivity();
        final List<MessageVersionItem> versions = MessageHistoryController.getVersions(dialogId, messageId);
        if (versions.isEmpty()) {
            AlertUtil.showToast(LocaleController.getString(R.string.MessageHistoryEmpty));
            return;
        }

        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(8), AndroidUtilities.dp(22), AndroidUtilities.dp(8));

        for (MessageVersionItem v : versions) {
            final TextView header = new TextView(context);
            header.setTextColor(Theme.getColor(Theme.key_dialogTextBlue));
            header.setTextSize(13);
            header.setTypeface(AndroidUtilities.bold());
            final String stamp = LocaleController.formatDateTime(v.observedAt / 1000, true);
            header.setText(LocaleController.formatString("MessageHistoryVersion", R.string.MessageHistoryVersion, v.version) + "  ·  " + stamp);
            content.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 2));

            final TextView body = new TextView(context);
            body.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            body.setTextSize(15);
            body.setTextIsSelectable(true);
            final StringBuilder sb = new StringBuilder();
            final String mediaLabel = mediaLabel(v.mediaType);
            if (!TextUtils.isEmpty(mediaLabel)) {
                sb.append("[").append(mediaLabel).append("]");
                if (!TextUtils.isEmpty(v.text)) sb.append("\n");
            }
            if (!TextUtils.isEmpty(v.text)) sb.append(v.text);
            body.setText(sb.toString());
            content.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        final ScrollView scroll = new ScrollView(context);
        scroll.addView(content);

        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(TextUtils.isEmpty(title) ? LocaleController.getString(R.string.MessageHistoryViewEdits) : title);
        builder.setView(scroll);
        builder.setPositiveButton(LocaleController.getString(R.string.Close), null);
        builder.setNeutralButton(LocaleController.getString(R.string.Copy), (dialog, which) -> {
            final MessageVersionItem last = versions.get(versions.size() - 1);
            AndroidUtilities.addToClipboard(last.text);
            AlertUtil.showToast(LocaleController.getString(R.string.TextCopied));
        });
        fragment.showDialog(builder.create());
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
