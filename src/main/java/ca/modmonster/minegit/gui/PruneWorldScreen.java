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
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;

public class PruneWorldScreen extends Screen implements ProgressMonitor {
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 8 + 9 + 8 + 20 + 4, 60);

    private final Screen parent;
    private final LevelStorageSource.LevelStorageAccess levelAccess;
    private final BooleanConsumer callback;

    private MultiLineTextWidget descriptionWidget;
    private Button confirmButton;
    private Button cancelButton;
    private StringWidget statusWidget;
    private String statusText = "";
    private int taskWork = 0;
    private int taskTotalWork = 0;

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
        confirmButton = Button.builder(Component.translatable("minegit.prune.confirm"), button -> doPrune()).build();
        buttonRowLayout.addChild(confirmButton);

        // Cancel button
        cancelButton = Button.builder(Component.translatable("minegit.prune.cancel"), button -> onClose()).build();
        buttonRowLayout.addChild(cancelButton);

        statusWidget = new StringWidget(Component.empty(), this.font);
        columnLayout.addChild(statusWidget);

        // Add layout widgets
        this.layout.visitWidgets(this::addRenderableWidget);
        this.layout.arrangeElements();
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void doPrune() {
        cancelButton.active = false;
        confirmButton.active = false;
        levelAccess.safeClose();

        String worldId = levelAccess.getLevelId();
        new Thread(() -> {
            boolean ok = GitManager.prune(minecraft, worldId, this);
            if (ok) {
                minecraft.getToastManager().addToast(new SystemToast(new SystemToast.SystemToastId(), Component.translatable("minegit.prune.complete"), null));
            } else {
                minecraft.getToastManager().addToast(new SystemToast(new SystemToast.SystemToastId(), Component.translatable("minegit.prune.failed"), null));
            }
            minecraft.submit(() -> this.callback.accept(true));
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

    @Override
    public void start(int totalTasks) {

    }

    @Override
    public void beginTask(String title, int totalWork) {
        statusText = title;
        taskWork = 0;
        taskTotalWork = totalWork;
        update(0);
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Override
    public void update(int completed) {
        taskWork += completed;
        minecraft.submit(() -> {
            String t = statusText;

            if (taskTotalWork > 0) {
                t += " (" + taskWork + " / " + taskTotalWork + ")";
            }

            statusWidget.setMessage(Component.literal(t));
            repositionElements();
        });
    }

    @Override
    public void endTask() {

    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    @Override
    public void showDuration(boolean enabled) {

    }
}
