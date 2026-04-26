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
import ca.modmonster.minegit.gui.TwoChoiceScreen;

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
            int status = GitManager.pull(minecraft, worldId, progressScreen);
            GitManager.makeWritable(minecraft, worldId);
            switch (status) {
                case 0:
                    // Success; load world as normal
                    doLoadWorld();
                    break;
                case 1:
                    // Failed because of merge conflicts; show toast saying "error :("
                    minecraft.submit(() -> list.returnToScreen());
                    minecraft.getToastManager().addToast(new SystemToast(new SystemToast.SystemToastId(), Component.translatable("minegit.sync.status.git_pull.error"), null));
                    break;
                case 2:
                    // Network error; show unreachable screen
                    minecraft.submit(() -> minecraft.setScreen(new TwoChoiceScreen(
                            Component.translatable("minegit.sync.unreachable.title"),
                            Component.translatable("minegit.sync.unreachable.description"),
                            Component.translatable("minegit.sync.unreachable.continue"),
                            Component.translatable("minegit.sync.unreachable.cancel"),
                            this::doLoadWorld, // continue
                            () -> minecraft.submit(() -> list.returnToScreen()) // cancel
                    )));
                    break;
            }
        }).start();
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void doLoadWorld() {
        minecraft.submit(() -> minecraft.createWorldOpenFlows().openWorld(getLevelSummary().getLevelId(), list::returnToScreen));
    }
}
