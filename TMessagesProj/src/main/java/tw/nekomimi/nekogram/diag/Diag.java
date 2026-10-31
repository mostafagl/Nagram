package tw.nekomimi.nekogram.diag;

import org.telegram.messenger.BuildConfig;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * The diagnostic build (package ".diag", installed next to Parvagram) records every error the
 * server returns, so a failure like "CHANNELS_TOO_MUCH" can be traced exactly. In the normal
 * build none of this runs.
 */
public final class Diag {

    public static final boolean ENABLED = BuildConfig.APPLICATION_ID.endsWith(".diag");

    private static final ArrayList<String> log = new ArrayList<>();
    private static final int MAX = 300;

    private Diag() {
    }

    public static String time() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    public static synchronized void log(String line) {
        if (!ENABLED) {
            return;
        }
        log.add(time() + "  " + line);
        while (log.size() > MAX) {
            log.remove(0);
        }
    }

    /** Called for every error a request gets (AlertsCreator.processError). */
    public static void onError(int account, TLRPC.TL_error error, TLObject request) {
        if (!ENABLED || error == null) {
            return;
        }
        log("ERROR acc=" + account + " request=" + (request == null ? "null" : request.getClass().getSimpleName())
                + describe(request) + " -> code=" + error.code + " text=" + error.text);
    }

    static String describe(TLObject request) {
        if (request instanceof TLRPC.TL_channels_joinChannel) {
            final TLRPC.InputChannel ch = ((TLRPC.TL_channels_joinChannel) request).channel;
            return ch == null ? "" : " channel_id=" + ch.channel_id;
        }
        return "";
    }

    public static synchronized String dump() {
        final StringBuilder sb = new StringBuilder();
        for (String l : log) {
            sb.append(l).append('\n');
        }
        return sb.toString();
    }
}
