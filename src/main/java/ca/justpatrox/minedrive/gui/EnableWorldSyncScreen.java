package ca.justpatrox.minedrive.gui;

import ca.justpatrox.minedrive.data.Config;
import ca.justpatrox.minedrive.data.ConfigManager;
import ca.justpatrox.minedrive.data.GitManager;
import ca.justpatrox.minedrive.data.NetworkManager;
import ca.justpatrox.minedrive.data.OAuthManager;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;

public class EnableWorldSyncScreen extends Screen {
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 8 + 9 + 8 + 20 + 4, 60);

    private final Screen parent;
    private final LevelSummary level;
    private final Runnable closeCallback;

    private Button confirmButton;
    private Button cancelButton;
    private Button openSetupButton;

    public EnableWorldSyncScreen(Screen parent, LevelSummary level, Runnable closeCallback) {
        super(Component.translatable("minegit.sync.enable.title"));
        this.parent = parent;
        this.level = level;
        this.closeCallback = closeCallback;
    }

    @Override
    protected void init() {
        LinearLayout columnLayout = this.layout.addToContents(LinearLayout.vertical().spacing(8));
        columnLayout.defaultCellSetting().alignHorizontallyCenter();

        layout.addTitleHeader(this.title, this.font);

        columnLayout.addChild(new StringWidget(Component.translatable("minegit.sync.enable.confirm.line1", level.getLevelName()), this.font));
        columnLayout.addChild(new StringWidget(Component.translatable("minegit.sync.enable.confirm.line2"), this.font));

        LinearLayout buttonRowLayout = columnLayout.addChild(LinearLayout.horizontal().spacing(8));
        confirmButton = Button.builder(Component.translatable("minegit.sync.enable.confirm.ok"), button -> setupSync()).build();
        buttonRowLayout.addChild(confirmButton);

        cancelButton = Button.builder(Component.translatable("minegit.sync.enable.confirm.cancel"), button -> onClose()).build();
        buttonRowLayout.addChild(cancelButton);

        openSetupButton = Button.builder(Component.translatable("minegit.link.setup.open"), button -> minecraft.setScreen(new AccountLinkScreen(this.parent, closeCallback))).build();
        openSetupButton.visible = false;
        columnLayout.addChild(openSetupButton);

        this.layout.visitWidgets(this::addRenderableWidget);
        this.layout.arrangeElements();
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void setupSync() {
        confirmButton.active = false;
        cancelButton.active = false;

        GitProgressScreen progressScreen = new GitProgressScreen(Component.translatable("minegit.sync.enable.working"));
        minecraft.setScreen(progressScreen);

        new Thread(() -> {
            Config config = ConfigManager.getCurrentConfig();
            String accessToken = OAuthManager.getValidAccessToken(config);
            if (accessToken.isBlank()) {
                minecraft.submit(() -> {
                    minecraft.getToastManager().addToast(new SystemToast(new SystemToast.SystemToastId(), Component.literal("Google login required. Open Cloud Sync Setup."), null));
                    openSetupButton.visible = true;
                    cancelButton.active = true;
                    minecraft.setScreen(this);
                });
                return;
            }

            progressScreen.beginTask("Creating Google Drive folder", 0);
            String folderId = NetworkManager.createWorldFolder(accessToken, level.getLevelId(), level.getLevelName());
            if (folderId == null) {
                minecraft.submit(() -> {
                    minecraft.getToastManager().addToast(new SystemToast(new SystemToast.SystemToastId(), Component.translatable("minegit.sync.enable.create_repo.error", -1), null));
                    openSetupButton.visible = true;
                    cancelButton.active = true;
                    minecraft.setScreen(this);
                });
                return;
            }

            progressScreen.beginTask("Uploading initial snapshot", 0);
            boolean ok = GitManager.init(minecraft, level.getLevelId(), folderId, progressScreen);
            if (!ok) {
                minecraft.submit(() -> {
                    minecraft.getToastManager().addToast(new SystemToast(new SystemToast.SystemToastId(), Component.translatable("minegit.sync.enable.git_init.error"), null));
                    minecraft.setScreen(this);
                    cancelButton.active = true;
                });
                return;
            }

            minecraft.submit(() -> {
                minecraft.getToastManager().addToast(new SystemToast(new SystemToast.SystemToastId(), Component.translatable("minegit.sync.enable.complete"), null));
                onClose();
            });
        }).start();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
        if (closeCallback != null) closeCallback.run();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
    }
}
