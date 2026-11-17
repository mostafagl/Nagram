package org.telegram.utils.proxy;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.ParcelFileDescriptor;

import org.telegram.messenger.ApplicationLoader;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import libv2ray.DirectSocketBinder;

/** Per-test socket routing; never changes the process default network or the running core. */
final class DirectProxyNetwork implements DirectSocketBinder {
    private final ConnectivityManager manager;
    private final Network network;
    final AtomicBoolean denied = new AtomicBoolean();
    // A failed first address does not prove every address for the config is unreachable.
    final AtomicBoolean multipleAddresses = new AtomicBoolean();
    final java.util.concurrent.atomic.AtomicInteger boundSockets = new java.util.concurrent.atomic.AtomicInteger();

    private DirectProxyNetwork(ConnectivityManager manager, Network network) {
        this.manager = manager;
        this.network = network;
    }

    static DirectProxyNetwork acquire() {
        try {
            ConnectivityManager manager = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return null;
            Network active = manager.getActiveNetwork();
            if (active != null && eligible(manager.getNetworkCapabilities(active))) return new DirectProxyNetwork(manager, active);
            Network selected = null;
            int best = -1;
            for (Network candidate : manager.getAllNetworks()) {
                NetworkCapabilities caps = manager.getNetworkCapabilities(candidate);
                if (!eligible(caps)) continue;
                int rank = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? 10 : 0;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) rank += 3;
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) rank += 2;
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) rank += 1;
                if (rank > best) { selected = candidate; best = rank; }
            }
            return selected == null ? null : new DirectProxyNetwork(manager, selected);
        } catch (RuntimeException denied) {
            return null;
        }
    }

    private static boolean eligible(NetworkCapabilities caps) {
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    @Override public boolean bindSocket(long fd) {
        // fromFd DUPLICATES; closing this wrapper must not close Go's original descriptor.
        try {
            if (fd < 0 || fd > Integer.MAX_VALUE || !eligible(manager.getNetworkCapabilities(network))) {
                denied.set(true);
                return false;
            }
            try (ParcelFileDescriptor duplicate = ParcelFileDescriptor.fromFd((int) fd)) {
                network.bindSocket(duplicate.getFileDescriptor());
                boundSockets.incrementAndGet();
                return true;
            }
        } catch (Exception e) {
            denied.set(true);
            return false;
        }
    }

    @Override public String resolveHost(String host) {
        try {
            InetAddress[] addresses = network.getAllByName(host);
            if (addresses.length > 1) multipleAddresses.set(true);
            if (addresses.length > 0) return addresses[0].getHostAddress();
        } catch (Exception e) {
            // DNS denial/timeout cannot prove a proxy is dead. Fail closed for deletion too.
            denied.set(true);
        }
        denied.set(true);
        return "";
    }
}
