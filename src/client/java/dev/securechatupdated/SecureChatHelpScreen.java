package dev.securechatupdated;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class SecureChatHelpScreen extends Screen {
    private static final float MAX_TEXT_SCALE = 0.84F;
    private static final float MIN_TEXT_SCALE = 0.55F;

    private final Screen returnScreen;
    private final List<String> lines;
    private final List<HelpEntry> entries = new ArrayList<>();
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int commandX;
    private int descriptionX;
    private int firstLineY;
    private int rowHeight;
    private float textScale;

    public SecureChatHelpScreen(Screen returnScreen, List<String> lines) {
        super(Component.literal("Secure Chat Help"));
        this.returnScreen = returnScreen;
        this.lines = lines;
    }

    @Override
    protected void init() {
        entries.clear();
        for (String line : lines) {
            if (!"Commands:".equals(line)) {
                entries.add(parseEntry(line));
            }
        }

        panelWidth = Math.min(620, this.width - 32);
        panelHeight = Math.min(this.height - 16, 76 + entries.size() * 13);
        panelX = (this.width - panelWidth) / 2;
        panelY = Math.max(8, (this.height - panelHeight) / 2);
        commandX = panelX + 22;
        descriptionX = panelX + Math.min(360, Math.max(250, panelWidth / 2 + 28));
        firstLineY = panelY + 42;

        int buttonY = panelY + panelHeight - 28;
        int availableRowsHeight = Math.max(1, buttonY - 8 - firstLineY);
        rowHeight = Math.max(7, availableRowsHeight / Math.max(1, entries.size()));
        textScale = computeTextScale();

        this.addRenderableWidget(Button.builder(
                Component.literal("Done"),
                button -> this.minecraft.setScreen(returnScreen)
        ).bounds((this.width - 80) / 2, buttonY, 80, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xB0000000);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 1, 0xAA55FF55);
        graphics.fill(panelX, panelY + panelHeight - 1, panelX + panelWidth, panelY + panelHeight, 0xAA55FF55);
        graphics.fill(panelX, panelY, panelX + 1, panelY + panelHeight, 0xAA55FF55);
        graphics.fill(panelX + panelWidth - 1, panelY, panelX + panelWidth, panelY + panelHeight, 0xAA55FF55);

        scaledText(graphics, "Secure Chat Commands", commandX, panelY + 12, 0xFF55FF55);
        scaledText(graphics, "Command", commandX, panelY + 27, 0xFFAAAAAA);
        scaledText(graphics, "Description", descriptionX, panelY + 27, 0xFFAAAAAA);

        int y = firstLineY;
        for (HelpEntry entry : entries) {
            scaledText(graphics, entry.command(), commandX, y, 0xFFFFFFFF);
            scaledText(graphics, entry.description(), descriptionX, y, 0xFFDDDDDD);
            y += rowHeight;
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static HelpEntry parseEntry(String line) {
        int split = line.indexOf("  ");
        if (split < 0) {
            return new HelpEntry(line, "");
        }

        String command = line.substring(0, split).trim();
        String description = line.substring(split).trim();
        return new HelpEntry(command, description);
    }

    private void scaledText(GuiGraphicsExtractor graphics, String text, int x, int y, int color) {
        graphics.pose().pushMatrix();
        graphics.pose().scale(textScale, textScale);
        graphics.text(this.font, text, Math.round(x / textScale), Math.round(y / textScale), color, false);
        graphics.pose().popMatrix();
    }

    private float computeTextScale() {
        float scale = MAX_TEXT_SCALE;
        int commandWidth = Math.max(1, descriptionX - commandX - 18);
        int descriptionWidth = Math.max(1, panelX + panelWidth - descriptionX - 18);

        for (HelpEntry entry : entries) {
            int measuredCommandWidth = Math.max(1, this.font.width(entry.command()));
            int measuredDescriptionWidth = Math.max(1, this.font.width(entry.description()));
            scale = Math.min(scale, commandWidth / (float) measuredCommandWidth);
            scale = Math.min(scale, descriptionWidth / (float) measuredDescriptionWidth);
        }

        scale = Math.min(scale, Math.max(MIN_TEXT_SCALE, (rowHeight - 1) / (float) this.font.lineHeight));
        return Math.max(MIN_TEXT_SCALE, Math.min(MAX_TEXT_SCALE, scale));
    }

    private record HelpEntry(String command, String description) {}
}
