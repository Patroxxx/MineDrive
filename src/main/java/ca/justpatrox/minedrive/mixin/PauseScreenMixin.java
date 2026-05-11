package ca.justpatrox.minedrive.mixin;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import net.minecraft.world.level.storage.LevelResource;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import ca.justpatrox.minedrive.data.GitManager;
import ca.justpatrox.minedrive.data.QuitState;

@Mixin(PauseScreen.class)
public class PauseScreenMixin extends Screen {
    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Shadow
    @Nullable
    private Button disconnectButton;

    private final Tooltip tooltip = Tooltip.create(Component.translatable("minegit.exit_without_push"));

    @Inject(at = @At("TAIL"), method = "extractRenderState", remap = false)
    private void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a, CallbackInfo info) {
        if (disconnectButton == null) return;
        if (!minecraft.isLocalServer()) return;
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null) return;
        if (!GitManager.syncEnabled(server.getWorldPath(LevelResource.ROOT))) return;
        if (!QuitState.altQuit) {
            disconnectButton.setTooltip(null);
            return;
        }
        disconnectButton.setTooltip(tooltip);

        if (disconnectButton.isHoveredOrFocused()) {
            // draw red border
            graphics.outline(
                    disconnectButton.getX(),
                    disconnectButton.getY(),
                    disconnectButton.getWidth(),
                    disconnectButton.getHeight(),
                    CommonColors.RED
            );
        }
    }

    @Override
    public boolean keyPressed(@NonNull KeyEvent event) {
        if (event.key() == InputConstants.KEY_LALT) QuitState.altQuit = true;
        return super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (event.key() == InputConstants.KEY_LALT) QuitState.altQuit = false;
        return super.keyReleased(event);
    }
}
