package de.emilo.spotifycontrol.gui.raster;

import static de.emilo.spotifycontrol.gui.raster.Sdf.circle;

/**
 * Signed distance shapes that are not icons.
 *
 * <p>The icon outlines now live in {@link VectorIcons} and are rasterized by
 * {@link VectorRaster}; what remains here is the plain disc used for avatars
 * and round buttons.
 */
public final class IconShapes {

    public static final Shape CIRCLE = circle(0.5, 0.5, 0.5);

    private IconShapes() {
    }
}
