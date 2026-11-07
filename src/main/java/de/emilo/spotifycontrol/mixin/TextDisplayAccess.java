package de.emilo.spotifycontrol.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Opens the private text, opacity and style setters of a text display. See {@link DisplayAccess}. */
@Mixin(Display.TextDisplay.class)
public interface TextDisplayAccess {

    @Invoker("setText")
    void spotifycontrol$setText(Component text);

    @Invoker("setTextOpacity")
    void spotifycontrol$setTextOpacity(byte opacity);

    @Accessor("DATA_BACKGROUND_COLOR_ID")
    static EntityDataAccessor<Integer> spotifycontrol$backgroundColorId() {
        throw new AssertionError();
    }

    @Accessor("DATA_STYLE_FLAGS_ID")
    static EntityDataAccessor<Byte> spotifycontrol$styleFlagsId() {
        throw new AssertionError();
    }
}
