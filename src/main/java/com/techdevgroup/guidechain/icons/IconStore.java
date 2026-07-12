package com.techdevgroup.guidechain.icons;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lazy on-disk blob cache for item icons, shared by every web orientation.
 *
 * <p>The first request for an id renders/fetches once via the {@link IconSource},
 * writes the PNG blob under {@code <dir>/item/<id>.png}, and serves it; every
 * later request — from any web view — is a straight disk read. Unavailable ids
 * return {@code null} (the caller serves a placeholder) and are not cached, so
 * a later cache revision can still fill them in.
 */
public final class IconStore
{
    private static final Logger LOG = Logger.getLogger(IconStore.class.getName());

    private final File itemDir;
    private final IconSource source;

    public IconStore(File baseDir, IconSource source)
    {
        this.itemDir = new File(baseDir, "item");
        this.source = source;
    }

    /** PNG bytes for an item id, or {@code null} if the source has no icon. */
    public byte[] item(int id)
    {
        if (id <= 0) return null;
        byte[] cached = readBlob(id);
        if (cached != null) return cached;
        return fetchAndStore(id);
    }

    private byte[] readBlob(int id)
    {
        File f = blob(id);
        if (!f.isFile()) return null;
        return slurp(f);
    }

    private byte[] fetchAndStore(int id)
    {
        byte[] png = fetch(id);
        if (png == null) return null;
        writeBlob(id, png);
        return png;
    }

    private byte[] fetch(int id)
    {
        try
        {
            return source.itemPng(id);
        }
        catch (IOException e)
        {
            LOG.log(Level.FINE, "icon fetch failed for " + id, e);
            return null;
        }
    }

    private void writeBlob(int id, byte[] png)
    {
        try
        {
            itemDir.mkdirs();
            Files.write(blob(id).toPath(), png);
        }
        catch (IOException e)
        {
            LOG.log(Level.FINE, "icon write failed for " + id, e);
        }
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

    private File blob(int id)
    {
        return new File(itemDir, id + ".png");
    }
}
