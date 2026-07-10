package com.techdevgroup.guidechain.web;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.techdevgroup.guidechain.store.GuideStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Embedded localhost web view over the shared {@link GuideStore}.
 *
 * <p>Uses only the JDK built-in {@code com.sun.net.httpserver} — no extra
 * dependencies. Binds to 127.0.0.1 exclusively; the port is configurable
 * (default {@value #DEFAULT_PORT}). Plain Java, zero RuneLite imports —
 * the same server runs inside the plugin and standalone.
 *
 * <h3>Routes</h3>
 * <pre>
 * GET  /                              app shell
 * GET  /static/{file}                 vendored htmx / css / js / license
 * GET  /fragments/chains              chain picker partial
 * GET  /fragments/plan                ordered task list partial
 * GET  /fragments/step/current        detail partial following the position
 * GET  /fragments/step/{gid}/{sid}    detail partial for one step
 * POST /actions/select-chain          form: chain=&lt;chainId&gt;
 * POST /actions/step/{gid}/{sid}/done mark done (advances if current)
 * POST /actions/step/{gid}/{sid}/skip mark skipped (advances if current)
 * POST /actions/step/{gid}/{sid}/back move back one step
 * POST /actions/refresh-guides        re-fetch guide content
 * GET  /api/state.json                full machine-readable state
 * </pre>
 *
 * <p>Action responses return the refreshed plan partial and set
 * {@code HX-Trigger: guide-store-changed} so other fragments re-fetch.
 *
 * <p>Hard rule unchanged: this is a read/annotate surface over guide state —
 * it never automates game input.
 */
public final class GuideWebServer
{
    public static final int DEFAULT_PORT = 7780;

    private static final Logger LOG = Logger.getLogger(GuideWebServer.class.getName());

    private final GuideStore store;
    private final WebFragments fragments;
    private final Gson gson;
    private final Runnable refreshAction;
    private final HttpServer server;
    private final ExecutorService executor;

    /**
     * @param refreshAction invoked by POST /actions/refresh-guides; inside the
     *                      plugin this re-fetches from the guides source, in
     *                      standalone mode it reloads the fixture. May be null.
     */
    public GuideWebServer(GuideStore store, int port, Gson gson, Runnable refreshAction) throws IOException
    {
        this.store = store;
        this.gson = gson;
        this.refreshAction = refreshAction;
        this.fragments = new WebFragments(store);
        this.executor = Executors.newFixedThreadPool(2, r ->
        {
            Thread t = new Thread(r, "guide-chain-web");
            t.setDaemon(true);
            return t;
        });
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        this.server.setExecutor(executor);
        this.server.createContext("/", this::route);
    }

    public void start()
    {
        server.start();
        LOG.info("Guide Chain web view listening on " + url());
    }

    public void stop()
    {
        server.stop(0);
        executor.shutdownNow();
        try
        {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    public String url()
    {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    private void route(HttpExchange ex)
    {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        try
        {
            if ("GET".equals(method))
            {
                if ("/".equals(path))                          { sendHtml(ex, fragments.shell()); return; }
                if (path.startsWith("/static/"))               { serveStatic(ex, path.substring("/static/".length())); return; }
                if ("/fragments/chains".equals(path))          { sendHtml(ex, fragments.chainsFragment()); return; }
                if ("/fragments/plan".equals(path))            { sendHtml(ex, fragments.planFragment()); return; }
                if (path.startsWith("/fragments/step/"))
                {
                    String key = decode(path.substring("/fragments/step/".length()));
                    sendHtml(ex, fragments.stepFragment(key));
                    return;
                }
                if ("/api/state.json".equals(path))
                {
                    send(ex, 200, "application/json; charset=utf-8", gson.toJson(store.stateJson()));
                    return;
                }
                if ("/api/metrics".equals(path))
                {
                    send(ex, 200, "text/plain; charset=utf-8", fragments.metricsLine());
                    return;
                }
            }
            else if ("POST".equals(method))
            {
                if ("/actions/select-chain".equals(path))
                {
                    String chain = formParams(ex).get("chain");
                    store.recordWebAction();
                    if (chain != null) store.selectChainById(chain);
                    sendPlanAfterAction(ex);
                    return;
                }
                if ("/actions/refresh-guides".equals(path))
                {
                    store.recordWebAction();
                    if (refreshAction != null)
                    {
                        try
                        {
                            refreshAction.run();
                        }
                        catch (RuntimeException e)
                        {
                            LOG.log(Level.WARNING, "refresh-guides action failed", e);
                        }
                    }
                    sendPlanAfterAction(ex);
                    return;
                }
                if (path.startsWith("/actions/step/"))
                {
                    // /actions/step/{guideId}/{stepId}/{done|skip|back}
                    String rest = decode(path.substring("/actions/step/".length()));
                    int slash = rest.lastIndexOf('/');
                    if (slash > 0)
                    {
                        String key = rest.substring(0, slash);
                        String action = rest.substring(slash + 1);
                        store.recordWebAction();
                        switch (action)
                        {
                            case "done": store.markDone(key);    sendPlanAfterAction(ex); return;
                            case "skip": store.markSkipped(key); sendPlanAfterAction(ex); return;
                            case "back": store.back();           sendPlanAfterAction(ex); return;
                            default: break;
                        }
                    }
                }
            }
            send(ex, 404, "text/plain; charset=utf-8", "not found");
        }
        catch (Exception e)
        {
            LOG.log(Level.WARNING, "Web request failed: " + method + " " + path, e);
            try
            {
                send(ex, 500, "text/plain; charset=utf-8", "internal error");
            }
            catch (IOException ignored)
            {
                // connection already gone
            }
        }
        finally
        {
            ex.close();
        }
    }

    /** Actions respond with the refreshed plan and poke other fragments. */
    private void sendPlanAfterAction(HttpExchange ex) throws IOException
    {
        ex.getResponseHeaders().set("HX-Trigger", "guide-store-changed");
        sendHtml(ex, fragments.planFragment());
    }

    // ── Static resources (vendored from the jar) ─────────────────────────────

    private void serveStatic(HttpExchange ex, String name) throws IOException
    {
        // whitelist — never expose arbitrary classpath entries
        String contentType;
        switch (name)
        {
            case "htmx.min.js":
            case "app.js":       contentType = "application/javascript; charset=utf-8"; break;
            case "app.css":      contentType = "text/css; charset=utf-8"; break;
            case "HTMX-LICENSE": contentType = "text/plain; charset=utf-8"; break;
            default:
                send(ex, 404, "text/plain; charset=utf-8", "not found");
                return;
        }
        try (InputStream in = GuideWebServer.class.getResourceAsStream("/web/" + name))
        {
            if (in == null)
            {
                send(ex, 404, "text/plain; charset=utf-8", "missing resource");
                return;
            }
            byte[] body = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.getResponseHeaders().set("Cache-Control", "max-age=3600");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody())
            {
                os.write(body);
            }
        }
    }

    // ── Plumbing ──────────────────────────────────────────────────────────────

    private void sendHtml(HttpExchange ex, String html) throws IOException
    {
        send(ex, 200, "text/html; charset=utf-8", html);
    }

    private void send(HttpExchange ex, int status, String contentType, String body) throws IOException
    {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody())
        {
            os.write(bytes);
        }
    }

    private Map<String, String> formParams(HttpExchange ex) throws IOException
    {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> params = new HashMap<>();
        for (String pair : body.split("&"))
        {
            int eq = pair.indexOf('=');
            if (eq > 0)
            {
                params.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        return params;
    }

    private static String decode(String s)
    {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
