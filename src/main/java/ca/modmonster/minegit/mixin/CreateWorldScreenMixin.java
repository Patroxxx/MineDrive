package ca.modmonster.minegit.mixin;

import ca.modmonster.minegit.data.Config;
import ca.modmonster.minegit.data.ConfigManager;
import ca.modmonster.minegit.gui.AccountLinkScreen;
import ca.modmonster.minegit.gui.CloneScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin extends Screen {
    @Unique
    @Nullable
    private Button gitButton;

    @Unique
    private boolean needsSetup = false;

    protected CreateWorldScreenMixin(Component title) {
        super(title);
    }

    @Inject(at = @At("TAIL"), method = "init", remap = false)
    private void init(CallbackInfo info) {
        checkNeedsSetup();

        if (needsSetup) {
            // Add setup button
            gitButton = Button.builder(Component.literal("☁"), this::onGitButtonPress)
                    .tooltip(Tooltip.create(Component.translatable("minegit.link.setup")))
                    .size(20, 20)
                    .build();
        } else {
            // Add clone button
            gitButton = Button.builder(Component.literal("↓"), this::onGitButtonPress)
                    .tooltip(Tooltip.create(Component.translatable("minegit.clone.title")))
                    .size(20, 20)
                    .build();
        }
        addRenderableWidget(gitButton);

        repositionElements();
    }

    @Unique
    void onGitButtonPress(Button gitButton) {
        if (needsSetup) {
            this.minecraft.setScreen(new AccountLinkScreen(this, this::updateSetupButton));
        } else {
            this.minecraft.setScreen(
                    new CloneScreen(this, null, () ->
                            minecraft.setScreen(new SelectWorldScreen(null))));
        }
    }

    @Unique
    void checkNeedsSetup() {
        Config config = ConfigManager.getCurrentConfig();
        needsSetup = config.username.isBlank() || config.getPat().isBlank();
    }

    @Unique
    void updateSetupButton() {
        if (gitButton == null) return;
        checkNeedsSetup();
        if (needsSetup) {
            gitButton.setMessage(Component.literal("☁"));
            gitButton.setTooltip(Tooltip.create(Component.translatable("minegit.link.setup")));
        } else {
            gitButton.setMessage(Component.literal("↓"));
            gitButton.setTooltip(Tooltip.create(Component.translatable("minegit.clone.title")));
        }
    }

    @Inject(at = @At("TAIL"), method = "repositionElements", remap = false)
    protected void repositionElements(CallbackInfo ci) {
        if (gitButton != null) gitButton.setPosition(width / 2 - 178, height - 26);
    }
}
