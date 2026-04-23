package ca.modmonster.minegit.mixin;

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
    private Button cloneButton;

    protected CreateWorldScreenMixin(Component title) {
        super(title);
    }

    @Inject(at = @At("TAIL"), method = "init", remap = false)
    private void init(CallbackInfo info) {
        // Add clone button
        cloneButton = Button.builder(Component.literal("↓"), button ->
                        this.minecraft.setScreen(
                                new CloneScreen(this, null, () ->
                                        minecraft.setScreen(new SelectWorldScreen(null))))
                        )
                .tooltip(Tooltip.create(Component.translatable("minegit.clone.title")))
                .size(20, 20)
                .build();
        addRenderableWidget(cloneButton);

        repositionElements();
    }

    @Inject(at = @At("TAIL"), method = "repositionElements", remap = false)
    protected void repositionElements(CallbackInfo ci) {
        if (cloneButton != null) cloneButton.setPosition(width / 2 - 178, height - 26);
    }
}
