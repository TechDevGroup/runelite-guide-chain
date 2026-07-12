package com.techdevgroup.guidechain.media;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolves the bytes behind a {@code GuideStep.media[n].path} entry
 * (FRAMES_GALLERY §4) — a lazy blob read, same family as {@code IconStore}
 * and {@code WikiPageStore}, but with no fetch step: the frame was already
 * captured and committed content-addressed into the guide source, so this is
 * pure resolution, not caching.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>On-disk guide source checkout — {@code <guidesDir>/<relativePath>}.
 *       This is the real path once guides live in a local git checkout
 *       (mirrors {@code GuideManager.GUIDES_DIR} on the plugin side —
 *       {@code stateFile().getParentFile()/guides}).</li>
 *   <li>Classpath fallback — {@code /fixtures/<relativePath>}, for the
 *       bundled fixture guide used by the standalone jar / {@code GuideWebMain}.</li>
 * </ol>
 *
 * <p>Path-traversal is unreachable by construction: the resolved on-disk file
 * must canonicalize to a descendant of {@code guidesDir}, same whitelist
 * discipline {@code serveStatic} already applies. Absent files return
 * {@code null} (honest 404 — the caller renders a "capture pending"
 * placeholder); nothing is cached here because the source file already is
 * the cache (content-addressed + immutable {@code Cache-Control} on the HTTP
 * response is what buys the actual caching win).
 */
public final class MediaStore
{
    private static final Logger LOG = Logger.getLogger(MediaStore.class.getName());

    private final File guidesDir;

    public MediaStore(File guidesDir)
    {
        this.guidesDir = guidesDir;
    }

    /** Bytes for a guide-source-relative media path, or {@code null} if absent from both sources. */
    public byte[] read(String relativePath)
    {
        if (relativePath == null || relativePath.isEmpty()) return null;
        byte[] onDisk = readOnDisk(relativePath);
        if (onDisk != null) return onDisk;
        return readClasspath(relativePath);
    }

    private byte[] readOnDisk(String relativePath)
    {
        File f = new File(guidesDir, relativePath);
        try
        {
            String base = guidesDir.getCanonicalPath();
            String resolved = f.getCanonicalPath();
            if (!resolved.startsWith(base + File.separator) && !resolved.equals(base))
            {
                return null; // outside the guide source — never reachable by construction
            }
        }
        catch (IOException e)
        {
            return null;
        }
        if (!f.isFile()) return null;
        try
        {
            return Files.readAllBytes(f.toPath());
        }
        catch (IOException e)
        {
            LOG.log(Level.FINE, "media read failed for " + f, e);
            return null;
        }
    }

    private byte[] readClasspath(String relativePath)
    {
        String resource = "/fixtures/" + relativePath;
        try (InputStream in = MediaStore.class.getResourceAsStream(resource))
        {
            return in != null ? in.readAllBytes() : null;
        }
        catch (IOException e)
        {
            LOG.log(Level.FINE, "media classpath read failed for " + resource, e);
            return null;
        }
    }
}
