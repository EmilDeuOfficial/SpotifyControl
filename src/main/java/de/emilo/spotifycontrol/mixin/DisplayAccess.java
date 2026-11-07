package de.emilo.spotifycontrol.mixin;

import com.mojang.math.Transformation;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Opens the private setters of display entities, so the world lyrics can configure a text display
 * that exists only on this client.
 *
 * <p>The intermediary names of every member used here are identical from 1.21.1 to 1.21.11, which
 * is what lets this single mixin, compiled once against 1.21.1, serve all six generations.
 */
@Mixin(Display.class)
public interface DisplayAccess {

    @Invoker("setBillboardConstraints")
    void spotifycontrol$setBillboard(Display.BillboardConstraints constraints);

    @Invoker("setBrightnessOverride")
    void spotifycontrol$setBrightness(Brightness brightness);

    @Invoker("setTransformation")
    void spotifycontrol$setTransformation(Transformation transformation);

    @Invoker("setViewRange")
    void spotifycontrol$setViewRange(float range);
}
