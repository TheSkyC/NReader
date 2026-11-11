package com.maxwai.nclientv3.utility.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.NonNull;

import com.maxwai.nclientv3.utility.LogUtility;

public class NetworkUtil {
    private volatile static ConnectionType type = ConnectionType.UNKNOWN;
    private static Network activeNetwork;

    public static ConnectionType getType() {
        return type;
    }

    public static synchronized void setType(ConnectionType x) {
        if (type == x) return;
        type = x;
        LogUtility.d("new Status: " + x);
    }

    static ConnectionType classifyCapabilities(NetworkCapabilities capabilities) {
        return capabilities == null ? ConnectionType.UNKNOWN : classifyCapabilities(
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
    }

    static ConnectionType classifyCapabilities(boolean internet, boolean validated, boolean cellular,
                                                boolean wifi, boolean ethernet, boolean notMetered) {
        if (!internet || !validated) return ConnectionType.UNKNOWN;
        if (cellular) return ConnectionType.CELLULAR;
        if (wifi || ethernet || notMetered) return notMetered ? ConnectionType.WIFI : ConnectionType.METERED;
        return ConnectionType.METERED;
    }

    public static void initConnectivity(@NonNull Context context) {
        context = context.getApplicationContext();
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            setType(ConnectionType.UNKNOWN);
            return;
        }

        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                super.onAvailable(network);
                synchronized (NetworkUtil.class) {
                    activeNetwork = network;
                    setType(ConnectionType.UNKNOWN);
                }
            }

            @Override
            public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities capabilities) {
                super.onCapabilitiesChanged(network, capabilities);
                synchronized (NetworkUtil.class) {
                    if (network.equals(activeNetwork)) setType(classifyCapabilities(capabilities));
                }
            }

            @Override
            public void onLost(@NonNull Network network) {
                super.onLost(network);
                synchronized (NetworkUtil.class) {
                    if (network.equals(activeNetwork)) {
                        activeNetwork = null;
                        setType(ConnectionType.UNKNOWN);
                    }
                }
            }
        };
        try {
            cm.registerDefaultNetworkCallback(callback);
            Network network = cm.getActiveNetwork();
            synchronized (NetworkUtil.class) {
                activeNetwork = network;
                setType(classifyCapabilities(network == null ? null : cm.getNetworkCapabilities(network)));
            }
        } catch (SecurityException e) {
            setType(ConnectionType.UNKNOWN);
            LogUtility.e("Problem initializing connectivity", e);
        }
    }

    public enum ConnectionType {WIFI, CELLULAR, METERED, UNKNOWN}

}
