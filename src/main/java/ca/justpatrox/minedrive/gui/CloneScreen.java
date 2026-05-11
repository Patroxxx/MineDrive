package ca.justpatrox.minedrive.gui;

import ca.justpatrox.minedrive.data.GitManager;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class CloneScreen extends Screen {
    private static final Component REPO_LABEL = Component.translatable("minegit.clone.repo");
    private static final Identifier RALSPIN = Identifier.fromNamespaceAndPath("minegit", "ralspin");
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 8 + 9 + 8 + 20 + 4, 60);

    private final Screen parent;
    private final Runnable closeCallback;
    private final Runnable cloneSuccessCallback;
    private EditBox repoEdit;
    private Button testCredentialsButton;
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
        // Column layout
        LinearLayout columnLayout = this.layout.addToContents(LinearLayout.vertical().spacing(8));
        columnLayout.defaultCellSetting().alignHorizontallyCenter();

        // Menu title
        layout.addTitleHeader(this.title, this.font);

        // Repo name text field
        StringWidget usernameEditLabel = columnLayout.addChild(new StringWidget(REPO_LABEL, font));
        usernameEditLabel.setAlpha(0.5f);
        repoEdit = new EditBox(font, 0, 0, 200, 20, REPO_LABEL);
        repoEdit.setMaxLength(256);
        repoEdit.setResponder(string -> updateButtonsStatus());
        columnLayout.addChild(repoEdit);

        // Clone button
        testCredentialsButton = Button.builder(Component.translatable("minegit.clone.confirm"), button -> doClone()).size(200, 20).build();
        columnLayout.addChild(testCredentialsButton);

        // Add layout widgets
        this.layout.visitWidgets(this::addRenderableWidget);

        // Back button
        Button backButton = Button.builder(Component.literal("←"), button -> onClose())
                .tooltip(Tooltip.create(Component.translatable("minegit.clone.back")))
                .bounds(6, 6, 20, 20)
                .build();
        addRenderableWidget(backButton);

        // Configure button
        configureButton = Button.builder(Component.literal("☁"), button -> minecraft.setScreen(new AccountLinkScreen(this)))
                .tooltip(Tooltip.create(Component.translatable("minegit.link.setup.open")))
                .bounds(6, width - 26, 20, 20)
                .build();
        addRenderableWidget(configureButton);

        // Ralsei go spinny
        ralspinWidget = ImageWidget.sprite(95, 95, RALSPIN);
        ralspinWidget.setPosition(width - 90, height - 95);
        ralspinWidget.setTooltip(Tooltip.create(Component.literal("yo")));
        addRenderableWidget(ralspinWidget);

        updateButtonsStatus();
        repositionElements();
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void doClone() {
        GitProgressScreen progressScreen = new GitProgressScreen(Component.translatable("minegit.clone.in_progress"));
        minecraft.setScreen(progressScreen);
        new Thread(() -> {
            int result;
            try {
                result = GitManager.cloneRepo(minecraft, repoEdit.getValue(), progressScreen);
            } catch (Exception e) {
                result = 2;
            }
            final int finalResult = result;

            minecraft.submit(() -> {
                if (finalResult == 0) {
                    minecraft.getToastManager().addToast(new SystemToast(new SystemToast.SystemToastId(), Component.translatable("minegit.clone.success"), null));
                    if (cloneSuccessCallback != null) {
                        cloneSuccessCallback.run();
                    } else {
                        onClose();
                    }
                } else if (finalResult == 1) {
                    minecraft.getToastManager().addToast(new SystemToast(new SystemToast.SystemToastId(), Component.translatable("minegit.clone.error.invalid_remote"), null));
                    minecraft.setScreen(this);
                    repositionElements();
                    updateButtonsStatus();
                } else {
                    minecraft.getToastManager().addToast(new SystemToast(new SystemToast.SystemToastId(), Component.translatable("minegit.clone.error.generic"), null));
                    minecraft.setScreen(this);
                    repositionElements();
                    updateButtonsStatus();
                }
            });
        }).start();
    }

    private void updateButtonsStatus() {
        testCredentialsButton.active = !repoEdit.getValue().isBlank();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
        if (closeCallback != null) closeCallback.run();
    }

    @Override
    protected void setInitialFocus() {
        setInitialFocus(repoEdit);
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
        ralspinWidget.setPosition(width - 90, height - 95);
        configureButton.setPosition(width - 26, 6);
    }
}
