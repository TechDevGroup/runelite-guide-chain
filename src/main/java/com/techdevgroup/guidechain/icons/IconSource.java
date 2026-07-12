package com.techdevgroup.guidechain.icons;

import java.io.IOException;

/**
 * Seam: produce raw PNG bytes for a game item id, or {@code null} when the
 * source has no icon for it.
 *
 * <p>The default impl {@link RepoIconSource} pulls from RuneLite's extracted
 * item-image repository (the icons RuneLite renders out of the game cache).
 * Inside the live client a client-backed source (ItemManager) can be dropped
 * in for fully-offline extraction straight from the loaded cache — same seam,
 * no other code changes.
 */
@FunctionalInterface
public interface IconSource
{
    byte[] itemPng(int itemId) throws IOException;
}
