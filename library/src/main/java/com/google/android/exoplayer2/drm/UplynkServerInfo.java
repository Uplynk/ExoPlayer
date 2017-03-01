package com.google.android.exoplayer2.drm;

public class UplynkServerInfo {

    public static String serverPrefix = "";

    public static String getWidevineURL()
    {
        if (serverPrefix.isEmpty())
            return "ERROR Server prefix was not set.";
        return serverPrefix + "/wv";
    }
}
