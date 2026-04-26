package ca.modmonster.minegit.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import ca.modmonster.minegit.data.GitManager;
import ca.modmonster.minegit.gui.GitProgressScreen;

@Mixin(WorldSelectionList.WorldListEntry.class)
public abstract class WorldListEntryMixin {
    @Shadow
    public abstract LevelSummary getLevelSummary();

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private WorldSelectionList list;

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Inject(method = "doDeleteWorld", at = @At("HEAD"))
    private void beforeWorldDelete(CallbackInfo ci) {
        // Make .git folder writable
        GitManager.makeWritable(minecraft, getLevelSummary().getLevelId());
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Inject(method = "joinWorld", at = @At("HEAD"), cancellable = true)
    private void beforeWorldJoin(CallbackInfo ci) {
        String worldId = getLevelSummary().getLevelId();
        if (!GitManager.syncEnabled(minecraft, worldId)) return;
        ci.cancel();
        GitProgressScreen progressScreen = new GitProgressScreen(Component.translatable("minegit.sync.status.git_pull"));
        minecraft.setScreen(progressScreen);
        new Thread(() -> {
            boolean ok = GitManager.pull(minecraft, worldId, progressScreen);
            GitManager.makeWritable(minecraft, worldId);
            if (ok) {
                // Continue loading the world
                minecraft.submit(() -> minecraft.createWorldOpenFlows().openWorld(getLevelSummary().getLevelId(), list::returnToScreen));
            } else {
                // Show toast saying "error :("
                minecraft.submit(() -> list.returnToScreen());
                minecraft.getToastManager().addToast(new SystemToast(new SystemToast.SystemToastId(), Component.translatable("minegit.sync.status.git_pull_error"), null));
            }
        }).start();
    }
}
