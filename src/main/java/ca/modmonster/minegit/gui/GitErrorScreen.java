package ca.modmonster.minegit.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class GitErrorScreen extends Screen {
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 8 + 9 + 8 + 20 + 4, 60);

    public GitErrorScreen(Runnable continueCallback, Runnable cancelCallback) {
        super(Component.translatable("minegit.sync.error.title"));
        this.continueCallback = continueCallback;
        this.cancelCallback = cancelCallback;
    }

    private final Runnable continueCallback;
    private final Runnable cancelCallback;

    @Override
    protected void init() {
        // Column layout
        LinearLayout columnLayout = this.layout.addToContents(LinearLayout.vertical().spacing(8));
        columnLayout.defaultCellSetting().alignHorizontallyCenter();

        // Menu title
        layout.addTitleHeader(this.title, this.font);

        // Confirmation message
        columnLayout.addChild(new MultiLineTextWidget(Component.translatable("minegit.sync.error.description"), this.font).setMaxWidth(this.width - 50));
        columnLayout.addChild(new SpacerElement(200, 20));

        // Continue button
        LinearLayout buttonRowLayout = columnLayout.addChild(LinearLayout.horizontal().spacing(8));
        Button continueButton = Button.builder(Component.translatable("minegit.sync.error.continue"), button -> continueCallback.run()).build();
        buttonRowLayout.addChild(continueButton);

        // Cancel button
        Button cancelButton = Button.builder(Component.translatable("minegit.sync.error.cancel"), button -> onClose()).build();
        buttonRowLayout.addChild(cancelButton);

        // Add layout widgets
        this.layout.visitWidgets(this::addRenderableWidget);
        this.layout.arrangeElements();
    }

    @Override
    public void onClose() {
        cancelCallback.run();
    }
}
