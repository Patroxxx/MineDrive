package ca.justpatrox.minedrive.mixin;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import ca.justpatrox.minedrive.data.Config;
import ca.justpatrox.minedrive.data.ConfigManager;
import ca.justpatrox.minedrive.data.GitManager;
import ca.justpatrox.minedrive.gui.AccountLinkScreen;
import ca.justpatrox.minedrive.gui.CloneScreen;
import ca.justpatrox.minedrive.gui.EnableWorldSyncScreen;
import ca.justpatrox.minedrive.widget.WorldSyncButtonState;

@Mixin(SelectWorldScreen.class)
public class SinglePlayerScreenMixin extends Screen {
    @Shadow
    private @Nullable WorldSelectionList list;

    protected SinglePlayerScreenMixin(Component title) {
        super(title);
    }

    @Unique @Nullable
    private Button cloneButton;

    @Unique @Nullable
    private Button worldSyncButton;

    @Unique
    private WorldSyncButtonState worldSyncButtonState = WorldSyncButtonState.SETUP;

    @Unique @Nullable
    private LevelSummary hoveredLevel;

    @Unique
    private boolean altHeld;

    @Inject(at = @At("TAIL"), method = "init", remap = false)
	private void init(CallbackInfo info) {
        // Add world sync button
        worldSyncButton = Button.builder(Component.literal("☁"), button -> {
            if (worldSyncButtonState == WorldSyncButtonState.SETUP || altHeld) {
                altHeld = false;
                this.minecraft.setScreen(new AccountLinkScreen(this, () -> {
                    if (this.list != null) this.list.returnToScreen();
                    updateWorldSyncButton();
                }));
            } else if (worldSyncButtonState == WorldSyncButtonState.ENABLE) {
                if (hoveredLevel != null) this.minecraft.setScreen(new EnableWorldSyncScreen(this, hoveredLevel, () -> {
                    if (this.list != null) this.list.returnToScreen();
                    updateWorldSyncButton();
                }));
            }
        }).size(20, 20).build();
        worldSyncButton.active = false;
        addRenderableWidget(worldSyncButton);

        // Add clone button
        cloneButton = Button.builder(Component.literal("↓"), button -> this.minecraft.setScreen(new CloneScreen(this, () -> {
            if (this.list != null) this.list.returnToScreen();
            updateWorldSyncButton();
        }))).tooltip(Tooltip.create(Component.translatable("minegit.clone.title")))
                .size(20, 20)
                .build();
        addRenderableWidget(cloneButton);

        repositionElements();
        updateWorldSyncButton();
	}

    @Inject(at = @At("TAIL"), method = "updateButtonStatus", remap = false)
    private void updateButtonStatus(LevelSummary levelSummary, CallbackInfo ci) {
        if (worldSyncButton == null) return;
        hoveredLevel = levelSummary;
        updateWorldSyncButton();
    }

    @Inject(at = @At("TAIL"), method = "repositionElements", remap = false)
    protected void repositionElements(CallbackInfo ci) {
        if (worldSyncButton != null) worldSyncButton.setPosition(width / 2 - 178, height - 52);
        if (cloneButton != null) cloneButton.setPosition(width / 2 - 178, height - 28);
    }

    @Unique
    private void updateWorldSyncButton() {
        if (worldSyncButton == null) return;
        if (altHeld) return;
        Config config = ConfigManager.getCurrentConfig();
        if (config.googleAccount.isBlank() || (config.getAccessToken().isBlank() && !config.hasRefreshToken())) {
            // Set the world sync button to configuration state
            worldSyncButtonState = WorldSyncButtonState.SETUP;
            this.worldSyncButton.active = true;
        } else if (hoveredLevel != null && GitManager.syncEnabled(minecraft, hoveredLevel.getLevelId())) {
            worldSyncButtonState = WorldSyncButtonState.WORLD_CONFIGURE;
            this.worldSyncButton.active = false;
        } else {
            worldSyncButtonState = WorldSyncButtonState.ENABLE;
            this.worldSyncButton.active = hoveredLevel != null;
        }

        worldSyncButtonState.apply(worldSyncButton);
        if (cloneButton != null) cloneButton.active = worldSyncButtonState != WorldSyncButtonState.SETUP;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_LALT) {
            altHeld = true;
            if (worldSyncButton != null) {
                worldSyncButton.active = true;
                worldSyncButton.setMessage(Component.literal("☁"));
                worldSyncButton.setTooltip(Tooltip.create(Component.translatable("minegit.link.setup.open")));
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (event.key() == InputConstants.KEY_LALT) {
            altHeld = false;
            updateWorldSyncButton();
        }
        return super.keyReleased(event);
    }
}
