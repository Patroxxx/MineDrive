package ca.modmonster.minegit.mixin;

import ca.modmonster.minegit.gui.PruneWorldScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EditWorldScreen.class)
public class EditWorldScreenMixin extends Screen {
    @Shadow
    @Final
    private LinearLayout layout;

    @Shadow
    @Final
    private LevelStorageSource.LevelStorageAccess levelAccess;

    protected EditWorldScreenMixin(Component title) {
        super(title);
    }

    @Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/layouts/LinearLayout;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;)Lnet/minecraft/client/gui/layouts/LayoutElement;", ordinal = 10), method = "<init>", remap = false)
    private void init(CallbackInfo info) {
        // Add clone button
        layout.addChild(Button.builder(Component.translatable("minegit.prune.button"), button ->
                        minecraft.setScreen(new PruneWorldScreen(this, levelAccess.getLevelId())))
                .width(200).build());

        layout.visitWidgets(this::addRenderableWidget);
    }
}
