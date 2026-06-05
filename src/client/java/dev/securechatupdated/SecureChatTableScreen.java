package dev.securechatupdated;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class SecureChatTableScreen extends Screen {
    public static final int WHITE = 0xFFFFFFFF;
    public static final int MUTED = 0xFFAAAAAA;
    public static final int GREEN = 0xFF55FF55;
    public static final int RED = 0xFFFF5555;
    public static final int ORANGE = 0xFFFFAA00;
    public static final int GRAY = 0xFF888888;

    private static final float MAX_TEXT_SCALE = 0.84F;
    private static final float MIN_TEXT_SCALE = 0.52F;

    private final Screen returnScreen;
    private final String heading;
    private final List<String> headers;
    private final List<List<Cell>> rows;

    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int firstLineY;
    private int rowHeight;
    private int[] columnX;
    private int[] columnWidth;
    private float textScale;

    public SecureChatTableScreen(Screen returnScreen, String heading, List<String> headers, List<List<Cell>> rows) {
        super(Component.literal(heading));
        this.returnScreen = returnScreen;
        this.heading = heading;
        this.headers = headers;
        this.rows = rows;
    }

    @Override
    protected void init() {
        int columns = Math.max(1, headers.size());
        panelWidth = Math.min(700, this.width - 32);
        panelHeight = Math.min(this.height - 16, 76 + rows.size() * 13);
        panelX = (this.width - panelWidth) / 2;
        panelY = Math.max(8, (this.height - panelHeight) / 2);
        firstLineY = panelY + 42;

        int buttonY = panelY + panelHeight - 28;
        int availableRowsHeight = Math.max(1, buttonY - 8 - firstLineY);
        rowHeight = Math.max(7, availableRowsHeight / Math.max(1, rows.size()));

        columnX = new int[columns];
        columnWidth = new int[columns];
        computeColumns(columns);
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

        scaledText(graphics, heading, panelX + 22, panelY + 12, GREEN);
        for (int i = 0; i < headers.size(); i++) {
            scaledText(graphics, headers.get(i), columnX[i], panelY + 27, MUTED);
        }

        int y = firstLineY;
        for (List<Cell> row : rows) {
            for (int i = 0; i < Math.min(row.size(), columnX.length); i++) {
                Cell cell = row.get(i);
                scaledText(graphics, cell.text(), columnX[i], y, cell.color());
            }
            y += rowHeight;
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void computeColumns(int columns) {
        int left = panelX + 22;
        int right = panelX + panelWidth - 22;
        int usable = Math.max(1, right - left);

        if (columns == 1) {
            columnX[0] = left;
            columnWidth[0] = usable;
            return;
        }

        if (columns == 2) {
            columnX[0] = left;
            columnWidth[0] = (int) (usable * 0.34F);
            columnX[1] = left + columnWidth[0] + 18;
            columnWidth[1] = right - columnX[1];
            return;
        }

        if (columns == 3) {
            columnX[0] = left;
            columnWidth[0] = (int) (usable * 0.20F);
            columnX[1] = left + columnWidth[0] + 14;
            columnWidth[1] = (int) (usable * 0.54F);
            columnX[2] = columnX[1] + columnWidth[1] + 14;
            columnWidth[2] = right - columnX[2];
            return;
        }

        columnX[0] = left;
        columnWidth[0] = (int) (usable * 0.16F);
        columnX[1] = left + columnWidth[0] + 12;
        columnWidth[1] = (int) (usable * 0.47F);
        columnX[2] = columnX[1] + columnWidth[1] + 12;
        columnWidth[2] = (int) (usable * 0.13F);
        columnX[3] = columnX[2] + columnWidth[2] + 12;
        columnWidth[3] = right - columnX[3];
    }

    private float computeTextScale() {
        float scale = MAX_TEXT_SCALE;

        for (int i = 0; i < headers.size(); i++) {
            scale = fitScale(scale, headers.get(i), columnWidth[i]);
        }
        for (List<Cell> row : rows) {
            for (int i = 0; i < Math.min(row.size(), columnWidth.length); i++) {
                scale = fitScale(scale, row.get(i).text(), columnWidth[i]);
            }
        }

        scale = Math.min(scale, Math.max(MIN_TEXT_SCALE, (rowHeight - 1) / (float) this.font.lineHeight));
        return Math.max(MIN_TEXT_SCALE, Math.min(MAX_TEXT_SCALE, scale));
    }

    private float fitScale(float current, String text, int width) {
        int measured = Math.max(1, this.font.width(text));
        return Math.min(current, Math.max(MIN_TEXT_SCALE, width / (float) measured));
    }

    private void scaledText(GuiGraphicsExtractor graphics, String text, int x, int y, int color) {
        graphics.pose().pushMatrix();
        graphics.pose().scale(textScale, textScale);
        graphics.text(this.font, text, Math.round(x / textScale), Math.round(y / textScale), color, false);
        graphics.pose().popMatrix();
    }

    public record Cell(String text, int color) {
        public static Cell of(String text) {
            return new Cell(text, WHITE);
        }
    }

    public static List<Cell> row(Cell... cells) {
        return new ArrayList<>(List.of(cells));
    }
}
