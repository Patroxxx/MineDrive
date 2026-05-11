package ca.justpatrox.minedrive.mixin;

import ca.justpatrox.minedrive.data.GitManager;
import ca.justpatrox.minedrive.data.SyncResult;
import ca.justpatrox.minedrive.gui.GitConflictScreen;
import ca.justpatrox.minedrive.gui.GitProgressScreen;
import ca.justpatrox.minedrive.gui.TwoChoiceScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.quickplay.QuickPlay;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(QuickPlay.class)
public class QuickPlayMixin {
    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Inject(method = "joinSingleplayerWorld", at = @At("HEAD"), cancellable = true)
    private static void joinSingleplayerWorld(final Minecraft minecraft, @Nullable final String identifier, CallbackInfo ci) {
        if (StringUtil.isBlank(identifier) || !minecraft.getLevelSource().levelExists(identifier)) return;
        if (!GitManager.syncEnabled(minecraft, identifier)) return;
        ci.cancel();
        GitProgressScreen progressScreen = new GitProgressScreen(Component.translatable("minegit.sync.status.git_pull"));
        minecraft.setScreen(progressScreen);
        new Thread(() -> {
            SyncResult status = GitManager.pull(GitManager.getPath(minecraft, identifier), progressScreen);
            GitManager.makeWritable(minecraft, identifier);
            switch (status) {
                case SUCCESS:
                    // Success; load world as normal
                    doLoadWorld(minecraft, identifier);
                    break;
                case FAIL_GENERIC:
                    // Generic error; show option to keep local or cloud
                    minecraft.submit(() -> minecraft.setScreen(new GitConflictScreen(
                            () -> doLoadWorld(minecraft, identifier),
                            () -> dontLoadWorld(minecraft),
                            GitManager.getPath(minecraft, identifier)
                    )));
                    break;
                case FAIL_NETWORK:
                    // Network error; show unreachable screen
                    minecraft.submit(() -> minecraft.setScreen(new TwoChoiceScreen(
                            Component.translatable("minegit.sync.pull_unreachable.title"),
                            Component.translatable("minegit.sync.pull_unreachable.description"),
                            Component.translatable("minegit.sync.pull_unreachable.continue"),
                            Component.translatable("minegit.sync.pull_unreachable.cancel"),
                            () -> doLoadWorld(minecraft, identifier), // continue
                            () -> dontLoadWorld(minecraft) // cancel
                    )));
                    break;
            }
        }).start();
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Unique
    private static void doLoadWorld(final Minecraft minecraft, final String identifier) {
        minecraft.submit(() -> minecraft.createWorldOpenFlows().openWorld(identifier, () -> minecraft.setScreen(new TitleScreen())));
    }

    @Unique
    private static void dontLoadWorld(final Minecraft minecraft) {
        minecraft.setScreen(new SelectWorldScreen(new TitleScreen()));
    }
}
