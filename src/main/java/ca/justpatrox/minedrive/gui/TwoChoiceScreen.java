package ca.justpatrox.minedrive.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class TwoChoiceScreen extends Screen {
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 8 + 9 + 8 + 20 + 4, 60);

    public TwoChoiceScreen(Component title, Component description, Component continueMessage, Component cancelMessage, Runnable continueCallback, Runnable cancelCallback) {
        super(title);
        this.description = description;
        this.continueMessage = continueMessage;
        this.cancelMessage = cancelMessage;
        this.continueCallback = continueCallback;
        this.cancelCallback = cancelCallback;
    }

    private final Component description;
    private final Component continueMessage;
    private final Component cancelMessage;
    private final Runnable continueCallback;
    private final Runnable cancelCallback;

    private MultiLineTextWidget descriptionWidget;

    @Override
    protected void init() {
        // Column layout
        LinearLayout columnLayout = this.layout.addToContents(LinearLayout.vertical().spacing(8));
        columnLayout.defaultCellSetting().alignHorizontallyCenter();

        // Menu title
        layout.addTitleHeader(this.title, this.font);

        // Confirmation message
        descriptionWidget = new MultiLineTextWidget(description, this.font).setMaxWidth(this.width - 50);
        columnLayout.addChild(descriptionWidget);
        columnLayout.addChild(new SpacerElement(200, 20));

        // Continue button
        LinearLayout buttonRowLayout = columnLayout.addChild(LinearLayout.horizontal().spacing(8));
        Button continueButton = Button.builder(continueMessage, button -> continueCallback.run()).build();
        buttonRowLayout.addChild(continueButton);

        // Cancel button
        Button cancelButton = Button.builder(cancelMessage, button -> cancelCallback.run()).build();
        buttonRowLayout.addChild(cancelButton);

        // Add layout widgets
        this.layout.visitWidgets(this::addRenderableWidget);
        this.layout.arrangeElements();
    }

    @Override
    protected void repositionElements() {
        if (descriptionWidget != null) descriptionWidget.setMaxWidth(this.width - 50);
        layout.arrangeElements();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
