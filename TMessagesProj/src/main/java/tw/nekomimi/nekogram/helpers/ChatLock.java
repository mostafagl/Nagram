package tw.nekomimi.nekogram.helpers;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * Chats locked with a password (and optionally the fingerprint). A locked chat asks for it each time
 * it is opened after the app was in the background; it can also keep its notifications, last
 * message and name out of sight. The password is stored only as a salted hash.
 */
public final class ChatLock {

    private static final String PREFS = "parva_chatlock";
    private static final HashSet<String> unlocked = new HashSet<>();
    private static HashSet<String> locked;

    private ChatLock() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(int account, long dialogId) {
        return account + ":" + dialogId;
    }

    private static synchronized HashSet<String> lockedSet() {
        if (locked == null) {
            locked = new HashSet<>(prefs().getStringSet("dialogs", new HashSet<>()));
        }
        return locked;
    }

    public static boolean hasPassword() {
        return prefs().contains("hash");
    }

    private static String hash(String salt, String password) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] data = (salt + ":" + password).getBytes("UTF-8");
            for (int i = 0; i < 10000; i++) {
                data = md.digest(data);
            }
            return Base64.encodeToString(data, Base64.NO_WRAP);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void setPassword(String password) {
        final byte[] saltBytes = new byte[16];
        new SecureRandom().nextBytes(saltBytes);
        final String salt = Base64.encodeToString(saltBytes, Base64.NO_WRAP);
        prefs().edit().putString("salt", salt).putString("hash", hash(salt, password)).apply();
    }

    public static boolean checkPassword(String password) {
        final String salt = prefs().getString("salt", "");
        final String stored = prefs().getString("hash", null);
        return stored != null && password != null && MessageDigest.isEqual(stored.getBytes(), hash(salt, password).getBytes());
    }

    public static boolean isLocked(int account, long dialogId) {
        return dialogId != 0 && lockedSet().contains(key(account, dialogId));
    }

    /** Locked and not opened with the password since the app was last in the background. */
    public static synchronized boolean needsUnlock(int account, long dialogId) {
        return isLocked(account, dialogId) && !unlocked.contains(key(account, dialogId));
    }

    public static synchronized void setLocked(int account, long dialogId, boolean lock) {
        final HashSet<String> set = lockedSet();
        if (lock) {
            set.add(key(account, dialogId));
        } else {
            set.remove(key(account, dialogId));
            unlocked.remove(key(account, dialogId));
        }
        prefs().edit().putStringSet("dialogs", new HashSet<>(set)).apply();
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload));
    }

    public static synchronized ArrayList<Long> getLockedDialogs(int account) {
        final ArrayList<Long> list = new ArrayList<>();
        final String prefix = account + ":";
        for (String k : lockedSet()) {
            if (k.startsWith(prefix)) {
                try {
                    list.add(Long.parseLong(k.substring(prefix.length())));
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return list;
    }

    public static synchronized void markUnlocked(int account, long dialogId) {
        unlocked.add(key(account, dialogId));
    }

    /** The app went to the background: every locked chat asks again. */
    public static synchronized void relockAll() {
        unlocked.clear();
    }

    public static boolean getOption(String option, boolean def) {
        return prefs().getBoolean(option, def);
    }

    public static void setOption(String option, boolean value) {
        prefs().edit().putBoolean(option, value).apply();
    }

    public static final String OPTION_FINGERPRINT = "fingerprint";
    public static final String OPTION_HIDE_NOTIFICATIONS = "hideNotifications";
    public static final String OPTION_HIDE_TEXT = "hideText";
    public static final String OPTION_HIDE_NAME = "hideName";

    public static boolean useFingerprint() {
        return getOption(OPTION_FINGERPRINT, true);
    }

    public static boolean hideNotifications(int account, long dialogId) {
        return getOption(OPTION_HIDE_NOTIFICATIONS, true) && isLocked(account, dialogId);
    }

    public static boolean hideText(int account, long dialogId) {
        return getOption(OPTION_HIDE_TEXT, true) && isLocked(account, dialogId);
    }

    public static boolean hideName(int account, long dialogId) {
        return getOption(OPTION_HIDE_NAME, false) && isLocked(account, dialogId);
    }

    public static boolean canUseFingerprint(Context context) {
        try {
            return Build.VERSION.SDK_INT >= 23 && BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Asks for the fingerprint (when allowed) or the password, then runs {@code onSuccess}. */
    public static void requestUnlock(BaseFragment fragment, int account, long dialogId, Runnable onSuccess) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final Runnable success = () -> {
            markUnlocked(account, dialogId);
            onSuccess.run();
        };
        if (useFingerprint() && canUseFingerprint(context) && context instanceof FragmentActivity) {
            try {
                final BiometricPrompt prompt = new BiometricPrompt((FragmentActivity) context, ContextCompat.getMainExecutor(context), new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        success.run();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_LOCKOUT
                                || errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT || errorCode == BiometricPrompt.ERROR_NO_BIOMETRICS) {
                            askPassword(fragment, success);
                        }
                    }
                });
                prompt.authenticate(new BiometricPrompt.PromptInfo.Builder()
                        .setTitle(LocaleController.getString(R.string.ChatLockUnlockTitle))
                        .setNegativeButtonText(LocaleController.getString(R.string.ChatLockUsePassword))
                        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                        .build());
                return;
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        askPassword(fragment, success);
    }

    private static EditText passwordField(Context context, int hint) {
        final EditText field = new EditText(context);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        field.setHint(LocaleController.getString(hint));
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setGravity(Gravity.CENTER);
        field.setSingleLine(true);
        return field;
    }

    private static void askPassword(BaseFragment fragment, Runnable onSuccess) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final EditText field = passwordField(context, R.string.ChatLockPassword);
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 8, 24, 8));
        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.ChatLockUnlockTitle))
                .setView(layout)
                .setPositiveButton(LocaleController.getString(R.string.OK), null)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        dialog.setOnShowListener(d -> {
            field.requestFocus();
            AndroidUtilities.showKeyboard(field);
        });
        fragment.showDialog(dialog);
        final android.view.View ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (ok == null) {
            return;
        }
        ok.setOnClickListener(v -> {
            if (checkPassword(field.getText().toString())) {
                AndroidUtilities.hideKeyboard(field);
                dialog.dismiss();
                onSuccess.run();
            } else {
                field.setText("");
                AndroidUtilities.shakeViewSpring(field, -6);
                Toast.makeText(context, LocaleController.getString(R.string.ChatLockWrongPassword), Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Sets (or changes, after the old one) the password, then runs {@code onDone}. */
    public static void showSetPassword(BaseFragment fragment, Runnable onDone) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        if (hasPassword()) {
            askPassword(fragment, () -> showNewPassword(fragment, onDone));
        } else {
            showNewPassword(fragment, onDone);
        }
    }

    private static void showNewPassword(BaseFragment fragment, Runnable onDone) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final EditText first = passwordField(context, R.string.ChatLockNewPassword);
        final EditText second = passwordField(context, R.string.ChatLockRepeatPassword);
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(first, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 8, 24, 4));
        layout.addView(second, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 8));
        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.ChatLockSetPassword))
                .setMessage(LocaleController.getString(R.string.ChatLockSetPasswordInfo))
                .setView(layout)
                .setPositiveButton(LocaleController.getString(R.string.Save), null)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        fragment.showDialog(dialog);
        final android.view.View save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (save == null) {
            return;
        }
        save.setOnClickListener(v -> {
            final String a = first.getText().toString();
            if (a.length() < 4) {
                AndroidUtilities.shakeViewSpring(first, -6);
                Toast.makeText(context, LocaleController.getString(R.string.ChatLockPasswordShort), Toast.LENGTH_SHORT).show();
                return;
            }
            if (!TextUtils.equals(a, second.getText().toString())) {
                second.setText("");
                AndroidUtilities.shakeViewSpring(second, -6);
                Toast.makeText(context, LocaleController.getString(R.string.ChatLockPasswordMismatch), Toast.LENGTH_SHORT).show();
                return;
            }
            setPassword(a);
            AndroidUtilities.hideKeyboard(first);
            dialog.dismiss();
            if (onDone != null) {
                onDone.run();
            }
        });
    }

    /** Locks or unlocks {@code dialogId} from a chat's menu, asking for a password first if none is set. */
    public static void toggleFromChat(BaseFragment fragment, int account, long dialogId, Runnable onChanged) {
        if (isLocked(account, dialogId)) {
            requestUnlock(fragment, account, dialogId, () -> {
                setLocked(account, dialogId, false);
                if (onChanged != null) {
                    onChanged.run();
                }
            });
        } else if (!hasPassword()) {
            showSetPassword(fragment, () -> {
                setLocked(account, dialogId, true);
                markUnlocked(account, dialogId);
                if (onChanged != null) {
                    onChanged.run();
                }
            });
        } else {
            setLocked(account, dialogId, true);
            markUnlocked(account, dialogId);
            if (onChanged != null) {
                onChanged.run();
            }
        }
    }
}
