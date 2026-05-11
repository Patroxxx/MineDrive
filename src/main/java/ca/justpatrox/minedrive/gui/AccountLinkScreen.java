package ca.justpatrox.minedrive.gui;

import ca.justpatrox.minedrive.data.Config;
import ca.justpatrox.minedrive.data.ConfigManager;
import ca.justpatrox.minedrive.data.OAuthManager;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ImageWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class AccountLinkScreen extends Screen {
    private static final Identifier RALSPIN = Identifier.fromNamespaceAndPath("minegit", "ralspin");
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 8 + 9 + 8 + 20 + 4, 60);

    private final Screen parent;
    private final Runnable closeCallback;

    private ImageWidget ralspinWidget;
    private StringWidget statusWidget;
    private Button connectButton;
    private Button disconnectButton;

    public AccountLinkScreen(Screen parent) {
        this(parent, null);
    }

    public AccountLinkScreen(Screen parent, Runnable closeCallback) {
        super(Component.translatable("minegit.link.title"));
        this.parent = parent;
        this.closeCallback = closeCallback;
    }

    @Override
    protected void init() {
        LinearLayout columnLayout = this.layout.addToContents(LinearLayout.vertical().spacing(8));
        columnLayout.defaultCellSetting().alignHorizontallyCenter();

        layout.addTitleHeader(this.title, this.font);

        Config config = ConfigManager.getCurrentConfig();
        String email = config.googleAccount == null ? "" : config.googleAccount;
        String status = email.isBlank() ? "Not connected" : "Connected as " + email;

        statusWidget = new StringWidget(Component.literal(status), font);
        columnLayout.addChild(statusWidget);

        connectButton = Button.builder(Component.literal("Connect with Google Drive"), button -> startOAuth()).size(220, 20).build();
        columnLayout.addChild(connectButton);

        disconnectButton = Button.builder(Component.literal("Disconnect"), button -> disconnect()).size(220, 20).build();
        disconnectButton.active = !email.isBlank();
        columnLayout.addChild(disconnectButton);

        this.layout.visitWidgets(this::addRenderableWidget);
        this.layout.arrangeElements();

        Button backButton = Button.builder(Component.literal("←"), button -> onClose())
                .tooltip(Tooltip.create(Component.translatable("minegit.link.back")))
                .bounds(6, 6, 20, 20)
                .build();
        addRenderableWidget(backButton);

        ralspinWidget = ImageWidget.sprite(95, 95, RALSPIN);
        ralspinWidget.setPosition(width - 90, height - 95);
        ralspinWidget.setTooltip(Tooltip.create(Component.literal("yo")));
        addRenderableWidget(ralspinWidget);
    }

    private void startOAuth() {
        connectButton.active = false;
        statusWidget.setMessage(Component.literal("Opening browser for Google login..."));

        new Thread(() -> {
            OAuthManager.AuthResult result = OAuthManager.connectInteractive();
            minecraft.submit(() -> {
                if (result.success) {
                    statusWidget.setMessage(Component.literal("Connected as " + result.email));
                    disconnectButton.active = true;
                } else {
                    statusWidget.setMessage(Component.literal(result.message));
                }
                connectButton.active = true;
                layout.arrangeElements();
            });
        }, "MineDrive-OAuth").start();
    }

    private void disconnect() {
        Config config = ConfigManager.getCurrentConfig();
        config.clearGoogleSession();
        ConfigManager.save(config);
        statusWidget.setMessage(Component.literal("Not connected"));
        disconnectButton.active = false;
        layout.arrangeElements();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
        if (closeCallback != null) closeCallback.run();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
        ralspinWidget.setPosition(width - 90, height - 95);
    }
}
