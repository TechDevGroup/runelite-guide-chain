package com.techdevgroup.guidechain.wiki;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lazy on-disk blob cache of rendered OSRS wiki article HTML.
 *
 * <p>The first request for a title hits the MediaWiki parse API, wraps the
 * article body in a minimal dark-theme shell, writes it to
 * {@code <cacheDir>/<slug>.html}, and returns the bytes.
 * Every later request is a straight disk read. 404/error responses are
 * not cached, so a retry will attempt the fetch again.
 *
 * <p>The OSRS wiki sends {@code X-Frame-Options: DENY} + {@code frame-ancestors 'none'}
 * which blocks direct iframing. Serving from localhost avoids the restriction.
 */
public final class WikiPageStore
{
    private static final Logger LOG = Logger.getLogger(WikiPageStore.class.getName());

    private static final String API_BASE =
        "https://oldschool.runescape.wiki/api.php"
        + "?action=parse&page=%s&prop=text&format=json&formatversion=2&redirects=1";

    private static final int TIMEOUT_MS = 8000;

    private final File cacheDir;

    public WikiPageStore(File cacheDir)
    {
        this.cacheDir = cacheDir;
    }

    /**
     * Returns the cached-or-freshly-fetched HTML bytes for the given wiki
     * page title, or {@code null} if the page is not found / API error.
     */
    public byte[] page(String title)
    {
        if (title == null || title.isEmpty()) return null;
        File blob = blobFile(title);
        if (blob.isFile()) return slurp(blob);
        return fetchAndStore(title, blob);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private File blobFile(String title)
    {
        return new File(cacheDir, slug(title) + ".html");
    }

    /** URL-safe filename slug: replace non-alphanum chars with underscores. */
    static String slug(String title)
    {
        return title.replaceAll("[^A-Za-z0-9._\\-]", "_");
    }

    private byte[] fetchAndStore(String title, File blob)
    {
        try
        {
            String html = fetchFromApi(title);
            if (html == null) return null;
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            cacheDir.mkdirs();
            Files.write(blob.toPath(), bytes);
            return bytes;
        }
        catch (IOException e)
        {
            LOG.log(Level.WARNING, "wiki fetch failed for: " + title, e);
            return null;
        }
    }

    private static String fetchFromApi(String title) throws IOException
    {
        String encoded = URLEncoder.encode(title, StandardCharsets.UTF_8);
        String apiUrl = String.format(API_BASE, encoded);
        HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "guide-chain");
        try
        {
            if (conn.getResponseCode() != 200) return null;
            try (InputStream in = conn.getInputStream())
            {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return parseAndWrap(json, title);
            }
        }
        finally
        {
            conn.disconnect();
        }
    }

    /** Extract parse.text from the MediaWiki JSON response and wrap in dark shell. */
    private static String parseAndWrap(String json, String title)
    {
        // Instance API, not JsonParser.parseString: RuneLite's repo bundles gson 2.8.5
        // and the static method only exists from 2.8.6 (NoSuchMethodError on the box).
        @SuppressWarnings("deprecation")
        JsonObject root = new JsonParser().parse(json).getAsJsonObject();
        if (root.has("error")) return null;   // API error → don't cache
        JsonObject parse = root.getAsJsonObject("parse");
        if (parse == null || !parse.has("text")) return null;
        String body = parse.get("text").getAsString();
        return darkShell(body);
    }

    private static String darkShell(String body)
    {
        return "<!DOCTYPE html>\n"
            + "<html><head>"
            + "<meta charset=\"utf-8\">"
            + "<base href=\"https://oldschool.runescape.wiki/\" target=\"_blank\">"
            + "<style>"
            + "body{background:#14171c;color:#e8e6e3;font:15px/1.5 system-ui;padding:1rem;max-width:52rem}"
            + "a{color:#35b5ff}"
            + "img{max-width:100%}"
            + "table{border-collapse:collapse}"
            + "td,th{border:1px solid #313847;padding:.2rem .4rem}"
            + "</style>"
            + "</head><body>\n"
            + body
            + "\n</body></html>";
    }

    private static byte[] slurp(File f)
    {
        try
        {
            return Files.readAllBytes(f.toPath());
        }
        catch (IOException e)
        {
            return null;
        }
    }
}
