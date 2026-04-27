package ca.modmonster.minegit.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelStorageSource;

import org.eclipse.jgit.lib.ProgressMonitor;

import ca.modmonster.minegit.data.GitManager;
import ca.modmonster.minegit.data.SyncResult;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;

public class PruneWorldScreen extends Screen {
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 8 + 9 + 8 + 20 + 4, 60);

    private final Screen parent;
    private final LevelStorageSource.LevelStorageAccess levelAccess;
    private final BooleanConsumer callback;

    private MultiLineTextWidget descriptionWidget;

    public PruneWorldScreen(Screen parent, LevelStorageSource.LevelStorageAccess levelAccess, BooleanConsumer callback) {
        super(Component.translatable("minegit.prune.title"));
        this.parent = parent;
        this.levelAccess = levelAccess;
        this.callback = callback;
    }

    @Override
    protected void init() {
        // Column layout
        LinearLayout columnLayout = this.layout.addToContents(LinearLayout.vertical().spacing(8));
        columnLayout.defaultCellSetting().alignHorizontallyCenter();

        // Menu title
        layout.addTitleHeader(this.title, this.font);

        // Confirmation message
        descriptionWidget = new MultiLineTextWidget(Component.translatable("minegit.prune.description"), this.font).setMaxWidth(this.width - 50);
        columnLayout.addChild(descriptionWidget);
        columnLayout.addChild(new SpacerElement(200, 20));

        // Confirm button
        LinearLayout buttonRowLayout = columnLayout.addChild(LinearLayout.horizontal().spacing(8));
        Button confirmButton = Button.builder(Component.translatable("minegit.prune.confirm"), button -> pullThenPrune()).build();
        buttonRowLayout.addChild(confirmButton);

        // Cancel button
        Button cancelButton = Button.builder(Component.translatable("minegit.prune.cancel"), button -> onClose()).build();
        buttonRowLayout.addChild(cancelButton);

        StringWidget statusWidget = new StringWidget(Component.empty(), this.font);
        columnLayout.addChild(statusWidget);

        // Add layout widgets
        this.layout.visitWidgets(this::addRenderableWidget);
        this.layout.arrangeElements();
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void doPrune(ProgressMonitor progress) {
        String worldId = levelAccess.getLevelId();
        boolean ok = GitManager.prune(minecraft, worldId, progress);
        if (ok) {
            minecraft.getToastManager().addToast(new SystemToast(new SystemToast.SystemToastId(), Component.translatable("minegit.prune.complete"), null));
        } else {
            minecraft.getToastManager().addToast(new SystemToast(new SystemToast.SystemToastId(), Component.translatable("minegit.prune.failed"), null));
        }
        minecraft.submit(() -> this.callback.accept(true));
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void pullThenPrune() {
        levelAccess.safeClose();
        String worldId = levelAccess.getLevelId();

        GitProgressScreen progressScreen = new GitProgressScreen(Component.translatable("minegit.prune.in_progress"));
        minecraft.setScreen(progressScreen);
        new Thread(() -> {
            SyncResult status = GitManager.pull(GitManager.getPath(minecraft, worldId), progressScreen);
            GitManager.makeWritable(minecraft, worldId);
            switch (status) {
                case SUCCESS:
                    // Success; load world as normal
                    doPrune(progressScreen);
                    break;
                case FAIL_GENERIC:
                    // Generic error; show option to keep local or cloud
                    minecraft.submit(() -> minecraft.setScreen(new GitConflictScreen(
                            () -> doPrune(progressScreen),
                            this::onClose,
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
                            () -> doPrune(progressScreen),
                            this::onClose
                    )));
                    break;
            }
        }).start();
    }

    @Override
    protected void repositionElements() {
        if (descriptionWidget != null) descriptionWidget.setMaxWidth(this.width - 50);
        layout.arrangeElements();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
