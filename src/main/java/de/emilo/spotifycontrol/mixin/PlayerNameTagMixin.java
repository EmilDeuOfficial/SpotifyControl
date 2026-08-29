package de.emilo.spotifycontrol.mixin;

import de.emilo.spotifycontrol.client.StreamMarker;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puts the streaming glyph in front of the party host's name tag.
 *
 * <p>The renderer that draws name tags was rewritten twice inside the 1.21 line, and in 1.21.11 the
 * tag is no longer drawn from the entity at all but from a render state field. Every one of those
 * renderers still sources the text from {@code Player.getDisplayName()}, which is the one hook whose
 * signature is unchanged across all six supported generations, so the decoration is applied here
 * rather than at any renderer.
 */
@Mixin(Player.class)
public abstract class PlayerNameTagMixin {

    @Inject(method = "getDisplayName()Lnet/minecraft/network/chat/Component;",
            at = @At("RETURN"),
            cancellable = true)
    private void spotifycontrol$markStreamingHost(CallbackInfoReturnable<Component> info) {
        Component name = info.getReturnValue();
        if (name == null) {
            return;
        }
        Component marked = StreamMarker.decorate(((Player) (Object) this).getUUID(), name);
        // StreamMarker hands back the very same instance when there is nothing to mark, so an
        // identity check is enough to keep the untouched path free of any callback work.
        if (marked != name) {
            info.setReturnValue(marked);
        }
    }
}
