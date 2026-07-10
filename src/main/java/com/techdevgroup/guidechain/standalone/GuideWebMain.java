package com.techdevgroup.guidechain.standalone;

import com.google.gson.Gson;
import com.techdevgroup.guidechain.data.ChainEntry;
import com.techdevgroup.guidechain.data.Guide;
import com.techdevgroup.guidechain.data.Manifest;
import com.techdevgroup.guidechain.store.CharacterSnapshot;
import com.techdevgroup.guidechain.store.GuideStore;
import com.techdevgroup.guidechain.web.GuideWebServer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Portability proof: boots the shared {@link GuideStore} and the embedded
 * {@link GuideWebServer} entirely outside RuneLite, seeded with the bundled
 * f2p-early-game fixture guide. No game client is attached, so live
 * condition values render as "client offline" — everything else (plan,
 * navigation, done/skip marks, persistence, state API) works identically.
 *
 * <pre>
 * java -cp runelite-guide-chain-&lt;ver&gt;-standalone.jar \
 *      com.techdevgroup.guidechain.standalone.GuideWebMain [--port 7780] [--dir &lt;stateDir&gt;]
 * # or:  ./gradlew runWeb
 * </pre>
 *
 * State persists to {@code &lt;stateDir&gt;/state.json}
 * (default {@code ~/.runelite/guide-chain-standalone/}, kept separate from
 * the plugin's state so the two never fight over one file).
 */
public final class GuideWebMain
{
    private GuideWebMain() {}

    public static void main(String[] args) throws IOException, InterruptedException
    {
        int port = GuideWebServer.DEFAULT_PORT;
        File dir = new File(System.getProperty("user.home"), ".runelite/guide-chain-standalone");
        for (int i = 0; i < args.length - 1; i++)
        {
            if ("--port".equals(args[i])) port = Integer.parseInt(args[i + 1]);
            if ("--dir".equals(args[i]))  dir = new File(args[i + 1]);
        }

        Gson gson = new Gson();
        GuideStore store = new GuideStore(dir, gson);
        seedFixture(store, gson);

        GuideWebServer server = new GuideWebServer(store, port, gson,
            () -> seedFixture(store, gson));
        server.start();

        System.out.println("Guide Chain standalone web view: " + server.url());
        System.out.println("State file: " + store.stateFile());
        System.out.println("No game client attached — live condition values show as 'client offline'.");
        Thread.currentThread().join();
    }

    /** Load the bundled fixture manifest + guides from classpath resources. */
    static void seedFixture(GuideStore store, Gson gson)
    {
        Manifest manifest = readResource(gson, "/fixtures/manifest.json", Manifest.class);
        if (manifest == null)
        {
            System.err.println("Fixture manifest missing from classpath");
            return;
        }
        Map<String, Guide> guides = new LinkedHashMap<>();
        for (ChainEntry chain : manifest.chains())
        {
            for (String file : chain.guides())
            {
                String id = file.endsWith(".json") ? file.substring(0, file.length() - 5) : file;
                Guide g = readResource(gson, "/fixtures/" + file, Guide.class);
                if (g != null) guides.put(id, g);
            }
        }
        store.setPlan(manifest, guides);

        // Seed a last-known character snapshot once, so the fixture state
        // demonstrates the full shape of /api/state.json.
        if (store.character() == null)
        {
            CharacterSnapshot snap = new CharacterSnapshot();
            snap.playerName = "Fixture Adventurer";
            snap.loggedIn = false;
            snap.skills.put("COOKING", 12);
            snap.skills.put("WOODCUTTING", 9);
            snap.skills.put("FIREMAKING", 7);
            snap.skills.put("FISHING", 5);
            snap.skills.put("MINING", 4);
            snap.quests.put("COOKS_ASSISTANT", "IN_PROGRESS");
            snap.updatedAtMs = System.currentTimeMillis();
            store.updateCharacter(snap);
        }
        store.setClientConnected(false);
    }

    private static <T> T readResource(Gson gson, String path, Class<T> type)
    {
        try (InputStream in = GuideWebMain.class.getResourceAsStream(path))
        {
            if (in == null) return null;
            return gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), type);
        }
        catch (IOException e)
        {
            System.err.println("Failed to read fixture " + path + ": " + e);
            return null;
        }
    }
}
