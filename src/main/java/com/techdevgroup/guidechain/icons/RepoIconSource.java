package com.techdevgroup.guidechain.icons;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Item icons from RuneLite's extracted item-image repository — the same icons
 * RuneLite renders from the game cache, hosted per cache revision.
 *
 * <p>One network fetch per id; {@link IconStore} persists each blob so this is
 * hit at most once per item across the app's lifetime. A non-200 (e.g. an id
 * with no icon) returns {@code null} rather than caching a miss, so a later
 * cache revision can fill it in.
 */
public final class RepoIconSource implements IconSource
{
    private static final String BASE = "https://static.runelite.net/cache/item/icon/";
    private static final int TIMEOUT_MS = 4000;

    @Override
    public byte[] itemPng(int itemId) throws IOException
    {
        HttpURLConnection conn = open(itemId);
        try
        {
            if (conn.getResponseCode() != 200) return null;
            try (InputStream in = conn.getInputStream())
            {
                return in.readAllBytes();
            }
        }
        finally
        {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(int itemId) throws IOException
    {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE + itemId + ".png").openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "guide-chain");
        return conn;
    }
}
