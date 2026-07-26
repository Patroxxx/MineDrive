package ca.justpatrox.minedrive.gui;

import ca.justpatrox.minedrive.data.Config;
import ca.justpatrox.minedrive.data.ConfigManager;
import ca.justpatrox.minedrive.data.GitManager;
import ca.justpatrox.minedrive.data.NetworkManager;
import ca.justpatrox.minedrive.data.OAuthManager;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public class CloneScreen extends Screen {
    private static final Component WORLD_LIST_LABEL = Component.translatable("minegit.clone.world_list");
    private static final Identifier RALSPIN = Identifier.fromNamespaceAndPath("minegit", "ralspin");
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 8 + 9 + 8 + 20 + 4, 60);

    private final Screen parent;
    private final Runnable closeCallback;
    private final Runnable cloneSuccessCallback;
    private final List<NetworkManager.WorldFolderInfo> worldFolders = new ArrayList<>();
    private int selectedWorldIndex = -1;
    private boolean loadingWorlds = false;
    private StringWidget worldLabelWidget;
    private StringWidget selectedWorldWidget;
    private StringWidget statusWidget;
    private Button cloneButton;
    private Button deleteButton;
    private Button previousButton;
    private Button nextButton;
    private Button refreshButton;
    private ImageWidget ralspinWidget;
    private Button configureButton;

    public CloneScreen(Screen parent, Runnable closeCallback) {
        this(parent, closeCallback, null);
    }

    public CloneScreen(Screen parent, Runnable closeCallback, Runnable cloneSuccessCallback) {
        super(Component.translatable("minegit.clone.title"));
        this.parent = parent;
        this.closeCallback = closeCallback;
        this.cloneSuccessCallback = cloneSuccessCallback;
    }

    @Override
    protected void init() {
        LinearLayout columnLayout = this.layout.addToContents(LinearLayout.vertical().spacing(8));
        columnLayout.defaultCellSetting().alignHorizontallyCenter();

        layout.addTitleHeader(this.title, this.font);

        worldLabelWidget = columnLayout.addChild(new StringWidget(WORLD_LIST_LABEL, font));
        worldLabelWidget.setAlpha(0.5f);

        selectedWorldWidget = new StringWidget(Component.translatable("minegit.clone.world.loading"), font);
        columnLayout.addChild(selectedWorldWidget);

        LinearLayout selectorButtons = columnLayout.addChild(LinearLayout.horizontal().spacing(8));
        previousButton = Button.builder(Component.literal("←"), button -> moveSelection(-1)).size(96, 20).build();
        nextButton = Button.builder(Component.literal("→"), button -> moveSelection(1)).size(96, 20).build();
        selectorButtons.addChild(previousButton);
        selectorButtons.addChild(nextButton);

        refreshButton = Button.builder(Component.translatable("minegit.clone.refresh"), button -> loadWorldsAsync()).size(200, 20).build();
        columnLayout.addChild(refreshButton);

        LinearLayout actionButtons = columnLayout.addChild(LinearLayout.horizontal().spacing(8));
        cloneButton = Button.builder(Component.translatable("minegit.clone.confirm"), button -> doClone()).size(96, 20).build();
        deleteButton = Button.builder(Component.translatable("minegit.clone.delete"), button -> askDeleteWorld()).size(96, 20).build();
        actionButtons.addChild(cloneButton);
        actionButtons.addChild(deleteButton);

        statusWidget = new StringWidget(Component.empty(), font);
        statusWidget.setAlpha(0.8f);
        columnLayout.addChild(statusWidget);

        this.layout.visitWidgets(this::addRenderableWidget);

        Button backButton = Button.builder(Component.literal("←"), button -> onClose())
                .tooltip(Tooltip.create(Component.translatable("minegit.clone.back")))
                .bounds(6, 6, 20, 20)
                .build();
        addRenderableWidget(backButton);

        configureButton = Button.builder(Component.literal("☁"), button -> minecraft.gui.setScreen(new AccountLinkScreen(this)))
                .tooltip(Tooltip.create(Component.translatable("minegit.link.setup.open")))
                .bounds(6, width - 26, 20, 20)
                .build();
        addRenderableWidget(configureButton);

        ralspinWidget = ImageWidget.sprite(95, 95, RALSPIN);
        ralspinWidget.setPosition(width - 90, height - 95);
        ralspinWidget.setTooltip(Tooltip.create(Component.literal("yo")));
        addRenderableWidget(ralspinWidget);

        updateButtonsStatus();
        repositionElements();
        loadWorldsAsync();
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void doClone() {
        NetworkManager.WorldFolderInfo selected = getSelectedWorld();
        if (selected == null) return;

        GitProgressScreen progressScreen = new GitProgressScreen(Component.translatable("minegit.clone.in_progress"));
        minecraft.gui.setScreen(progressScreen);
        new Thread(() -> {
            int result;
            try {
                result = GitManager.cloneRepo(minecraft, selected.id, progressScreen);
            } catch (Exception e) {
                result = 2;
            }
            final int finalResult = result;

            minecraft.submit(() -> {
                if (finalResult == 0) {
                    SystemToast.add(minecraft.gui.toastManager(), new SystemToast.SystemToastId(), Component.translatable("minegit.clone.success"), null);
                    if (cloneSuccessCallback != null) {
                        cloneSuccessCallback.run();
                    } else {
                        onClose();
                    }
                } else if (finalResult == 1) {
                    SystemToast.add(minecraft.gui.toastManager(), new SystemToast.SystemToastId(), Component.translatable("minegit.clone.error.invalid_remote"), null);
                    minecraft.gui.setScreen(this);
                    repositionElements();
                    updateButtonsStatus();
                } else {
                    SystemToast.add(minecraft.gui.toastManager(), new SystemToast.SystemToastId(), Component.translatable("minegit.clone.error.generic"), null);
                    minecraft.gui.setScreen(this);
                    repositionElements();
                    updateButtonsStatus();
                }
            });
        }).start();
    }

    private void loadWorldsAsync() {
        loadingWorlds = true;
        selectedWorldWidget.setMessage(Component.translatable("minegit.clone.world.loading"));
        statusWidget.setMessage(Component.literal(""));
        repositionElements();
        updateButtonsStatus();

        new Thread(() -> {
            Config config = ConfigManager.getCurrentConfig();
            String accessToken = OAuthManager.getValidAccessToken(config);
            if (accessToken.isBlank()) {
                minecraft.submit(() -> {
                    worldFolders.clear();
                    selectedWorldIndex = -1;
                    loadingWorlds = false;
                    selectedWorldWidget.setMessage(Component.translatable("minegit.clone.world.none"));
                    statusWidget.setMessage(Component.translatable("minegit.clone.world.login_required"));
                    repositionElements();
                    updateButtonsStatus();
                });
                return;
            }

            List<NetworkManager.WorldFolderInfo> fetched = NetworkManager.listWorldFolders(accessToken);
            minecraft.submit(() -> {
                worldFolders.clear();
                if (fetched != null) {
                    worldFolders.addAll(fetched);
                }

                selectedWorldIndex = worldFolders.isEmpty() ? -1 : 0;
                loadingWorlds = false;

                if (worldFolders.isEmpty()) {
                    selectedWorldWidget.setMessage(Component.translatable("minegit.clone.world.none"));
                    statusWidget.setMessage(Component.translatable("minegit.clone.world.empty_hint"));
                } else {
                    updateSelectedWorldLabel();
                    statusWidget.setMessage(Component.translatable("minegit.clone.world.count", worldFolders.size()));
                }
                repositionElements();
                updateButtonsStatus();
            });
        }, "MineDrive-WorldList").start();
    }

    private void moveSelection(int direction) {
        if (worldFolders.isEmpty()) return;
        selectedWorldIndex = (selectedWorldIndex + direction + worldFolders.size()) % worldFolders.size();
        updateSelectedWorldLabel();
        updateButtonsStatus();
    }

    private NetworkManager.WorldFolderInfo getSelectedWorld() {
        if (selectedWorldIndex < 0 || selectedWorldIndex >= worldFolders.size()) return null;
        return worldFolders.get(selectedWorldIndex);
    }

    private void updateSelectedWorldLabel() {
        NetworkManager.WorldFolderInfo selected = getSelectedWorld();
        if (selected == null) {
            selectedWorldWidget.setMessage(Component.translatable("minegit.clone.world.none"));
            return;
        }
        selectedWorldWidget.setMessage(Component.literal(selected.displayName));
        repositionElements();
    }

    private void askDeleteWorld() {
        NetworkManager.WorldFolderInfo selected = getSelectedWorld();
        if (selected == null) return;
        minecraft.gui.setScreen(new TwoChoiceScreen(
                Component.translatable("minegit.clone.delete.confirm.title"),
                Component.translatable("minegit.clone.delete.confirm.description", selected.displayName),
                Component.translatable("minegit.clone.delete.confirm.ok"),
                Component.translatable("minegit.clone.delete.confirm.cancel"),
                this::deleteSelectedWorld,
                () -> minecraft.gui.setScreen(this)
        ));
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void deleteSelectedWorld() {
        NetworkManager.WorldFolderInfo selected = getSelectedWorld();
        if (selected == null) {
            minecraft.gui.setScreen(this);
            return;
        }

        loadingWorlds = true;
        updateButtonsStatus();

        GitProgressScreen progressScreen = new GitProgressScreen(Component.translatable("minegit.clone.delete.in_progress"));
        minecraft.gui.setScreen(progressScreen);
        new Thread(() -> {
            Config config = ConfigManager.getCurrentConfig();
            String accessToken = OAuthManager.getValidAccessToken(config);
            boolean ok = !accessToken.isBlank() && NetworkManager.deleteWorldFolder(accessToken, selected.id);

            minecraft.submit(() -> {
                minecraft.gui.setScreen(this);
                if (ok) {
                    SystemToast.add(minecraft.gui.toastManager(), new SystemToast.SystemToastId(), Component.translatable("minegit.clone.delete.success"), null);
                    loadWorldsAsync();
                } else {
                    loadingWorlds = false;
                    statusWidget.setMessage(Component.translatable("minegit.clone.delete.failed"));
                    repositionElements();
                    updateButtonsStatus();
                    SystemToast.add(minecraft.gui.toastManager(), new SystemToast.SystemToastId(), Component.translatable("minegit.clone.delete.failed"), null);
                }
            });
        }, "MineDrive-DeleteWorld").start();
    }

    private void updateButtonsStatus() {
        boolean hasWorlds = !worldFolders.isEmpty();
        boolean canInteract = !loadingWorlds;
        cloneButton.active = canInteract && hasWorlds;
        deleteButton.active = canInteract && hasWorlds;
        previousButton.active = canInteract && worldFolders.size() > 1;
        nextButton.active = canInteract && worldFolders.size() > 1;
        refreshButton.active = canInteract;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
        if (closeCallback != null) closeCallback.run();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
        centerTextWidget(worldLabelWidget);
        centerTextWidget(selectedWorldWidget);
        centerTextWidget(statusWidget);
        ralspinWidget.setPosition(width - 90, height - 95);
        configureButton.setPosition(width - 26, 6);
    }

    private void centerTextWidget(StringWidget widget) {
        if (widget == null) return;
        widget.setX(this.width / 2 - widget.getWidth() / 2);
    }
}
