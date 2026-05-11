package ca.justpatrox.minedrive.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import ca.justpatrox.minedrive.data.GitManager;
import ca.justpatrox.minedrive.data.SyncResult;
import ca.justpatrox.minedrive.gui.GitConflictScreen;
import ca.justpatrox.minedrive.gui.GitProgressScreen;
import ca.justpatrox.minedrive.gui.TwoChoiceScreen;

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
        // Compatibility hook for old installs that may still have legacy files
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
            SyncResult status = GitManager.pull(GitManager.getPath(minecraft, worldId), progressScreen);
            GitManager.makeWritable(minecraft, worldId);
            switch (status) {
                case SUCCESS:
                    // Success; load world as normal
                    doLoadWorld();
                    break;
                case FAIL_GENERIC:
                    // Generic error; show option to keep local or cloud
                    minecraft.submit(() -> minecraft.setScreen(new GitConflictScreen(
                            this::doLoadWorld,
                            () -> list.returnToScreen(),
                            GitManager.getPath(minecraft, worldId)
                    )));
                    break;
                case FAIL_NETWORK:
                    // Network error; show unreachable screen
                    minecraft.submit(() -> minecraft.setScreen(new TwoChoiceScreen(
                            Component.translatable("minegit.sync.pull_unreachable.title"),
                            Component.translatable("minegit.sync.pull_unreachable.description"),
                            Component.translatable("minegit.sync.pull_unreachable.continue"),
                            Component.translatable("minegit.sync.pull_unreachable.cancel"),
                            this::doLoadWorld, // continue
                            () -> list.returnToScreen() // cancel
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
