package client.cn.nsimu.simukraft.client.mail;

import client.cn.nsimu.simukraft.client.city.map.SimuMapManager;
import client.cn.nsimu.simukraft.client.city.map.SimuMapRegion;
import com.mojang.blaze3d.platform.InputConstants;
import common.cn.nsimu.simukraft.SimuKraft;
import common.cn.nsimu.simukraft.network.mail.MailMessageData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/** 使用邮件素材绘制收件箱界面，并处理标签、邮件列表和回复输入。 */
public final class MailScreen extends Screen {
    private static final int BACKGROUND_WIDTH = 1920;
    private static final int BACKGROUND_HEIGHT = 1080;
    private static final Identifier BACKGROUND = texture("mail_background.png");
    private static final Identifier LABEL = texture("mail_label.png");
    private static final Identifier CHAT_BUBBLE = texture("chattxt.png");
    private static final Identifier[] TAB_ICONS = {
            texture("mail_citizen_icon.png"), texture("mail_system_icon.png"), texture("mail_city_icon.png")
    };
    private static final Identifier ARROW_UP = texture("mail_arrow_up.png");
    private static final Identifier ARROW_DOWN = texture("mail_arrow_down.png");
    private static final Identifier ARROW_UP_PRESSED = texture("mail_arrow_up_pressed.png");
    private static final Identifier ARROW_DOWN_PRESSED = texture("mail_arrow_down_pressed.png");
    private static final Identifier SCROLL_THUMB = texture("mail_scroll_thumb.png");
    private static final Identifier SCROLL_THUMB_PRESSED = texture("mail_scroll_thumb_pressed.png");
    private static final Identifier SCROLL_UP = texture("mail_scroll_up.png");
    private static final Identifier SCROLL_DOWN = texture("mail_scroll_down.png");
    private static final Identifier SCROLL_UP_PRESSED = texture("mail_scroll_up_pressed.png");
    private static final Identifier SCROLL_DOWN_PRESSED = texture("mail_scroll_down_pressed.png");
    private static final Identifier ENTER = texture("mail_enter.png");
    private static final Identifier ENTER_PRESSED = texture("mail_enter_pressed.png");
    private static final Identifier MINIMIZE = texture("mail_unpressed_minimize.png");
    private static final Identifier WINDOWED = texture("mail_unpressed_windowed.png");
    private static final Identifier CLOSE = texture("mail_unpressed_close.png");
    private static final Identifier MINIMIZE_PRESSED = texture("mail_minimize.png");
    private static final Identifier WINDOWED_PRESSED = texture("mail_windowed.png");
    private static final Identifier CLOSE_PRESSED = texture("mail_close.png");
    private static final int[][] TAB_BOUNDS = {{66, 353, 186, 172}, {66, 540, 186, 172}, {66, 727, 186, 172}};
    private static final int[][] LEFT_ARROW_BOUNDS = {{92, 248, 54, 41}, {92, 956, 54, 41}};
    private static final int[][] SCROLL_BUTTON_BOUNDS = {{1458, 245, 68, 59}, {1458, 918, 68, 59}};
    private static final int[] THUMB_BOUNDS = {1465, 319, 55, 84};
    private static final int THUMB_TRAVEL = 515;
    private static final int[] SCROLL_TRACK_BOUNDS = {1458, 319, 68, 599};
    private static final int[] MAP_BOUNDS = {1540, 250, 326, 204};
    private static final double MAP_BLOCK_SCALE = 0.36D;
    private static final int[] ENTER_BOUNDS = {1338, 902, 104, 65};
    private static final int[][] WINDOW_BOUNDS = {{1723, 24, 53, 53}, {1781, 24, 53, 53}, {1842, 23, 53, 53}};
    private static final int MAX_VISIBLE_MESSAGES = 10;
    private static final int CHAT_LINE_CHAR_LIMIT = 20;

    private final List<List<String>> conversations = new ArrayList<>();
    private final List<StringBuilder> drafts = new ArrayList<>();
    private final int openedUnreadCount;
    private final Identifier senderDimension;
    private final int senderX;
    private final int senderZ;
    private final List<MailMessageData> systemMessages;
    private final SimuMapManager mapManager = SimuMapManager.getInstance();
    private int activeTab;
    private int listOffset;
    private int selectedMailIndex;
    private int pressedControl = -1;
    private boolean draggingThumb;
    private double dragStartY;
    private int dragStartOffset;
    private boolean mapConsumerAcquired;

    public MailScreen(int openedUnreadCount, Identifier senderDimension, int senderX, int senderZ) {
        this(openedUnreadCount, senderDimension, senderX, senderZ, List.of());
    }

    public MailScreen(int openedUnreadCount, Identifier senderDimension, int senderX, int senderZ,
                      List<MailMessageData> systemMessages) {
        super(Component.empty());
        this.openedUnreadCount = Math.max(0, Math.min(99, openedUnreadCount));
        this.senderDimension = senderDimension;
        this.senderX = senderX;
        this.senderZ = senderZ;
        this.systemMessages = systemMessages == null ? List.of() : List.copyOf(systemMessages);
        for (int i = 0; i < TAB_BOUNDS.length; i++) {
            conversations.add(new ArrayList<>());
            drafts.add(new StringBuilder());
        }
        if (this.openedUnreadCount > 0) {
            conversations.get(0).add(Component.translatable("screen.simukraft.mail.message.test_body").getString());
        }
    }

    private static Identifier texture(String name) {
        return Identifier.fromNamespaceAndPath(SimuKraft.MOD_ID, "textures/gui/" + name);
    }

    /** 禁用原版菜单背景，避免模糊和暗色层覆盖邮件窗口。 */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    protected void init() {
        super.init();
        mapManager.init();
        if (senderDimension != null && Minecraft.getInstance().level != null
                && senderDimension.equals(Minecraft.getInstance().level.dimension().identifier())) {
            mapManager.acquireConsumer();
            mapConsumerAcquired = true;
            mapManager.forceScanArea(senderX >> 4, senderZ >> 4, 2);
            mapManager.forceRenderAll();
        }
    }

    /** 绘制完整背景、素材控件和邮件内容。 */
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, 0, 0, 0.0F, 0.0F, width, height,
                BACKGROUND_WIDTH, BACKGROUND_HEIGHT, BACKGROUND_WIDTH, BACKGROUND_HEIGHT);
        drawWindowButtons(graphics);
        drawTabs(graphics);
        drawArrowButtons(graphics);
        drawScrollbar(graphics);
        drawMailContent(graphics);
        drawSenderMap(graphics);
        drawInput(graphics);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawWindowButtons(GuiGraphicsExtractor graphics) {
        drawTexture(graphics, MINIMIZE, WINDOW_BOUNDS[0]);
        drawTexture(graphics, WINDOWED, WINDOW_BOUNDS[1]);
        drawTexture(graphics, CLOSE, WINDOW_BOUNDS[2]);
        if (pressedControl >= 0 && pressedControl <= 2) {
            Identifier texture = switch (pressedControl) {
                case 0 -> MINIMIZE_PRESSED;
                case 1 -> WINDOWED_PRESSED;
                default -> CLOSE_PRESSED;
            };
            drawTexture(graphics, texture, WINDOW_BOUNDS[pressedControl]);
        }
    }

    private void drawTabs(GuiGraphicsExtractor graphics) {
        for (int i = 0; i < TAB_BOUNDS.length; i++) {
            if (i == activeTab) {
                drawTexture(graphics, LABEL, TAB_BOUNDS[i]);
            }
            int[] bounds = TAB_BOUNDS[i];
            int iconSize = 64;
            int x = bounds[0] + (bounds[2] - iconSize) / 2 + 18;
            int y = bounds[1] + (bounds[3] - iconSize) / 2;
            graphics.blit(RenderPipelines.GUI_TEXTURED, TAB_ICONS[i], scaleX(x), scaleY(y), 0.0F, 0.0F,
                    scaleX(iconSize), scaleY(iconSize), 16, 16, 16, 16);
        }
    }

    private void drawArrowButtons(GuiGraphicsExtractor graphics) {
        drawTexture(graphics, pressedControl == 3 ? ARROW_UP_PRESSED : ARROW_UP, LEFT_ARROW_BOUNDS[0]);
        drawTexture(graphics, pressedControl == 4 ? ARROW_DOWN_PRESSED : ARROW_DOWN, LEFT_ARROW_BOUNDS[1]);
    }

    private void drawScrollbar(GuiGraphicsExtractor graphics) {
        drawTexture(graphics, pressedControl == 5 ? SCROLL_UP_PRESSED : SCROLL_UP, SCROLL_BUTTON_BOUNDS[0]);
        drawTexture(graphics, pressedControl == 6 ? SCROLL_DOWN_PRESSED : SCROLL_DOWN, SCROLL_BUTTON_BOUNDS[1]);
        int maxOffset = maxListOffset();
        int y = THUMB_BOUNDS[1] + (maxOffset == 0 ? 0 : Math.round(THUMB_TRAVEL * listOffset / (float) maxOffset));
        int[] thumb = {THUMB_BOUNDS[0], y, THUMB_BOUNDS[2], THUMB_BOUNDS[3]};
        drawTexture(graphics, draggingThumb ? SCROLL_THUMB_PRESSED : SCROLL_THUMB, thumb);
    }

    private void drawMailContent(GuiGraphicsExtractor graphics) {
        if (activeTab == 1) {
            drawSystemMailContent(graphics);
            return;
        }
        int count = messageCount();
        int listX = 316;
        int listY = 278;
        graphics.enableScissor(scaleX(listX), scaleY(listY), scaleX(1440), scaleY(882));
        if (count == 0) {
            drawCentered(graphics, Component.translatable("screen.simukraft.mail.empty"), 875, 580, 0xFF26383A);
        } else {
            int start = Math.min(listOffset, Math.max(0, count - MAX_VISIBLE_MESSAGES));
            int y = listY;
            for (int i = start; i < Math.min(count, start + MAX_VISIBLE_MESSAGES); i++) {
                String message = conversations.get(activeTab).get(i);
                List<String> lines = wrapChatMessage(message);
                int textWidth = lines.stream().mapToInt(font::width).max().orElse(font.width(" "));
                float scaleX = width / (float) BACKGROUND_WIDTH;
                float scaleY = height / (float) BACKGROUND_HEIGHT;
                int bubbleWidth = Math.max(64, Math.round((textWidth + 24.0F) / Math.max(0.01F, scaleX)));
                int bubbleHeight = Math.max(48, Math.round((lines.size() * (font.lineHeight + 2.0F) + 16.0F)
                        / Math.max(0.01F, scaleY)));
                boolean ownMessage = activeTab == 2;
                int x = ownMessage ? listX : 1430 - bubbleWidth;
                drawChatBubble(graphics, x, y, bubbleWidth, bubbleHeight, ownMessage);
                int screenX = scaleX(x);
                int screenY = scaleY(y);
                graphics.enableScissor(screenX + 10, screenY + 6,
                        scaleX(x + bubbleWidth) - 8, scaleY(y + bubbleHeight) - 4);
                for (int line = 0; line < lines.size(); line++) {
                    graphics.text(font, Component.literal(lines.get(line)), scaleX(x + 12),
                            screenY + 8 + line * (font.lineHeight + 2),
                            0xFF23383A, false);
                }
                graphics.disableScissor();
                y += bubbleHeight + 12;
            }
        }
        graphics.disableScissor();
    }

    /** 系统消息使用邮件列表和正文布局，不使用聊天气泡贴图。 */
    private void drawSystemMailContent(GuiGraphicsExtractor graphics) {
        int count = systemMessages.size();
        int start = Math.min(listOffset, Math.max(0, count - MAX_VISIBLE_MESSAGES));
        if (count == 0) {
            drawCentered(graphics, Component.translatable("screen.simukraft.mail.empty"), 900, 580, 0xFF26383A);
            return;
        }
        // 邮件列表和正文使用两个独立区域，避免正文覆盖列表或地图。
        graphics.enableScissor(scaleX(316), scaleY(270), scaleX(742), scaleY(880));
        int y = 286;
        for (int i = start; i < Math.min(count, start + MAX_VISIBLE_MESSAGES); i++) {
            MailMessageData mail = systemMessages.get(i);
            int color = mail.unread() ? 0xFF162B31 : 0xFF536467;
            int row = i == selectedMailIndex ? 0x332D8C8C : 0x00000000;
            if (row != 0) {
                graphics.fill(scaleX(320), scaleY(y - 8), scaleX(738), scaleY(y + 34), row);
            }
            graphics.text(font, Component.literal(truncate(mail.sender(), 190)), scaleX(330), scaleY(y), color, false);
            graphics.text(font, Component.literal(truncate(mail.subject(), 270)), scaleX(510), scaleY(y), color, false);
            graphics.text(font, Component.literal(formatMailTime(mail.timestamp())), scaleX(665), scaleY(y), 0xFF536467, false);
            y += 54;
        }
        graphics.disableScissor();

        MailMessageData selected = systemMessages.get(Math.min(selectedMailIndex, count - 1));
        int detailX = 760;
        graphics.enableScissor(scaleX(detailX), scaleY(270), scaleX(1435), scaleY(890));
        graphics.text(font, Component.literal(truncate(selected.subject(), 610)), scaleX(detailX), scaleY(300), 0xFF162B31, false);
        graphics.text(font, Component.literal("发件人: " + truncate(selected.sender(), 540)), scaleX(detailX), scaleY(340), 0xFF536467, false);
        int bodyY = 390;
        int bodyWidth = Math.max(120, scaleX(650));
        float guiScaleY = height / (float) BACKGROUND_HEIGHT;
        int lineAdvance = Math.max(18, Math.round((font.lineHeight + 3.0F) / Math.max(0.01F, guiScaleY)));
        for (String line : selected.body().split("\\R", -1)) {
            List<FormattedCharSequence> wrapped = font.split(Component.literal(line), bodyWidth);
            if (wrapped.isEmpty()) wrapped = List.of(Component.literal("").getVisualOrderText());
            for (FormattedCharSequence wrappedLine : wrapped) {
                graphics.text(font, wrappedLine, scaleX(detailX), scaleY(bodyY), 0xFF23383A, false);
                bodyY += lineAdvance;
                if (bodyY > 865) break;
            }
            if (bodyY > 865) break;
        }
        graphics.disableScissor();
    }

    private String truncate(String text, int maxWidth) {
        String safe = text == null ? "" : text;
        return font.width(safe) <= maxWidth ? safe : font.plainSubstrByWidth(safe, Math.max(1, maxWidth - font.width("..."))) + "...";
    }

    private String formatMailTime(long timestamp) {
        return timestamp <= 0 ? "" : "第 " + (timestamp / 24000L) + " 天";
    }

    private void drawChatBubble(GuiGraphicsExtractor graphics, int x, int y, int width, int height, boolean ownMessage) {
        int sourceX = ownMessage ? 0 : 64;
        graphics.blit(RenderPipelines.GUI_TEXTURED, CHAT_BUBBLE, scaleX(x), scaleY(y), sourceX, 0,
                scaleX(width), scaleY(height), 64, 64, 128, 128);
    }

    private List<String> wrapChatMessage(String message) {
        List<String> lines = new ArrayList<>();
        String[] paragraphs = message.split("\\R", -1);
        for (String paragraph : paragraphs) {
            StringBuilder line = new StringBuilder();
            int characters = 0;
            for (int offset = 0; offset < paragraph.length();) {
                int codePoint = paragraph.codePointAt(offset);
                offset += Character.charCount(codePoint);
                line.appendCodePoint(codePoint);
                if (++characters == CHAT_LINE_CHAR_LIMIT) {
                    lines.add(line.toString());
                    line.setLength(0);
                    characters = 0;
                }
            }
            if (line.length() > 0 || paragraph.isEmpty()) {
                lines.add(line.toString());
            }
        }
        return lines.isEmpty() ? List.of("") : lines;
    }

    private void drawSenderMap(GuiGraphicsExtractor graphics) {
        int mapX = MAP_BOUNDS[0];
        int mapY = MAP_BOUNDS[1];
        int mapWidth = MAP_BOUNDS[2];
        int mapHeight = MAP_BOUNDS[3];
        graphics.fill(scaleX(mapX), scaleY(mapY), scaleX(mapX + mapWidth), scaleY(mapY + mapHeight), 0xFF18232A);

        Minecraft minecraft = Minecraft.getInstance();
        if (openedUnreadCount > 0 && senderDimension != null && minecraft.level != null
                && senderDimension.equals(minecraft.level.dimension().identifier()) && SimuMapManager.isAvailable()) {
            double centerX = mapX + mapWidth / 2.0D;
            double centerY = mapY + mapHeight / 2.0D;
            graphics.enableScissor(scaleX(mapX), scaleY(mapY), scaleX(mapX + mapWidth), scaleY(mapY + mapHeight));
            for (SimuMapRegion region : mapManager.getAllRegions()) {
                if (!region.isImageLoaded() && !region.hasData()) {
                    continue;
                }
                double regionX = centerX + (region.regionX * 512.0D - senderX) * MAP_BLOCK_SCALE;
                double regionY = centerY + (region.regionZ * 512.0D - senderZ) * MAP_BLOCK_SCALE;
                double regionSize = 512.0D * MAP_BLOCK_SCALE;
                if (regionX + regionSize < mapX || regionX > mapX + mapWidth
                        || regionY + regionSize < mapY || regionY > mapY + mapHeight) {
                    continue;
                }
                region.touch();
                Identifier textureLocation = region.getTextureLocation(mapManager.tryAcquireTextureUploadSlot());
                if (textureLocation == null) {
                    continue;
                }
                drawMapRegion(graphics, textureLocation, regionX, regionY, regionSize);
            }
            graphics.disableScissor();
            drawSenderMarker(graphics, scaleX((int) Math.round(centerX)), scaleY((int) Math.round(centerY)));
        }
    }

    private void drawMapRegion(GuiGraphicsExtractor graphics, Identifier textureLocation, double x, double y, double size) {
        int x0 = scaleX((int) Math.round(x));
        int y0 = scaleY((int) Math.round(y));
        int x1 = scaleX((int) Math.round(x + size));
        int y1 = scaleY((int) Math.round(y + size));
        graphics.blit(textureLocation, x0, y0, x1, y1, 0.0F, 1.0F, 0.0F, 1.0F);
    }

    private void drawSenderMarker(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.fill(x - 5, y - 5, x + 5, y + 5, 0xFF202020);
        graphics.fill(x - 3, y - 3, x + 3, y + 3, 0xFFFFD400);
    }

    private void drawInput(GuiGraphicsExtractor graphics) {
        if (activeTab == 1) return;
        drawTexture(graphics, pressedControl == 7 ? ENTER_PRESSED : ENTER, ENTER_BOUNDS);
        StringBuilder input = drafts.get(activeTab);
        if (!input.isEmpty()) {
            graphics.enableScissor(scaleX(300), scaleY(902), scaleX(1328), scaleY(967));
            graphics.text(font, input.toString(), scaleX(312), scaleY(925), 0xFF17282B, false);
            graphics.disableScissor();
        }
    }

    private void drawTexture(GuiGraphicsExtractor graphics, Identifier texture, int[] bounds) {
        int x = scaleX(bounds[0]);
        int y = scaleY(bounds[1]);
        int w = scaleX(bounds[2]);
        int h = scaleY(bounds[3]);
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0.0F, 0.0F, w, h, bounds[2], bounds[3], bounds[2], bounds[3]);
    }

    private void drawCentered(GuiGraphicsExtractor graphics, Component text, int sourceX, int sourceY, int color) {
        graphics.centeredText(font, text, scaleX(sourceX), scaleY(sourceY), color);
    }

    private int scaleX(int sourceX) {
        return Math.round(sourceX * (float) width / BACKGROUND_WIDTH);
    }

    private int scaleY(int sourceY) {
        return Math.round(sourceY * (float) height / BACKGROUND_HEIGHT);
    }

    private int messageCount() {
        return activeTab == 1 ? systemMessages.size() : conversations.get(activeTab).size();
    }

    private int maxListOffset() {
        return Math.max(0, messageCount() - MAX_VISIBLE_MESSAGES);
    }

    private void selectTab(int tab) {
        activeTab = tab;
        listOffset = 0;
        selectedMailIndex = 0;
    }

    private void scrollList(int amount) {
        listOffset = Math.max(0, Math.min(maxListOffset(), listOffset + amount));
    }

    private int controlAt(double mouseX, double mouseY) {
        for (int i = 0; i < WINDOW_BOUNDS.length; i++) if (contains(WINDOW_BOUNDS[i], mouseX, mouseY)) return i;
        for (int i = 0; i < TAB_BOUNDS.length; i++) if (contains(TAB_BOUNDS[i], mouseX, mouseY)) return 10 + i;
        for (int i = 0; i < LEFT_ARROW_BOUNDS.length; i++) if (contains(LEFT_ARROW_BOUNDS[i], mouseX, mouseY)) return 3 + i;
        for (int i = 0; i < SCROLL_BUTTON_BOUNDS.length; i++) if (contains(SCROLL_BUTTON_BOUNDS[i], mouseX, mouseY)) return 5 + i;
        if (contains(ENTER_BOUNDS, mouseX, mouseY)) return 7;
        if (contains(thumbBounds(), mouseX, mouseY)) return 8;
        if (contains(SCROLL_TRACK_BOUNDS, mouseX, mouseY)) return 9;
        return -1;
    }

    private int[] thumbBounds() {
        int y = THUMB_BOUNDS[1] + (maxListOffset() == 0 ? 0 : Math.round(THUMB_TRAVEL * listOffset / (float) maxListOffset()));
        return new int[]{THUMB_BOUNDS[0], y, THUMB_BOUNDS[2], THUMB_BOUNDS[3]};
    }

    private boolean contains(int[] bounds, double mouseX, double mouseY) {
        double x = scaleX(bounds[0]);
        double y = scaleY(bounds[1]);
        return mouseX >= x && mouseX < x + scaleX(bounds[2])
                && mouseY >= y && mouseY < y + scaleY(bounds[3]);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT)
            return super.mouseClicked(event, doubleClick);
        double mouseX = event.x();
        double mouseY = event.y();
        int control = controlAt(mouseX, mouseY);
        if (control >= 0 && control <= 2) {
            pressedControl = control;
            return true;
        }
        if (control >= 3 && control <= 6) {
            pressedControl = control;
            scrollList(control == 3 || control == 5 ? -1 : 1);
            return true;
        }
        if (control == 7) {
            pressedControl = control;
            submitInput();
            return true;
        }
        if (control == 8) {
            draggingThumb = true;
            dragStartY = mouseY;
            dragStartOffset = listOffset;
            return true;
        }
        if (control == 9) {
            jumpToScrollPosition(mouseY);
            draggingThumb = true;
            dragStartY = mouseY;
            dragStartOffset = listOffset;
            return true;
        }
        if (control >= 10) {
            selectTab(control - 10);
            return true;
        }
        int row = rowAt(mouseY);
        if (row >= 0) {
            if (activeTab == 1) {
                selectedMailIndex = Math.min(systemMessages.size() - 1, listOffset + row);
            }
            return true;
        }
        return inputBoundsContains(mouseX, mouseY) || super.mouseClicked(event, doubleClick);
    }

    private int rowAt(double mouseY) {
        int top = scaleY(activeTab == 1 ? 278 : 260);
        int rowHeight = Math.max(1, scaleY(activeTab == 1 ? 54 : 34));
        if (mouseY < top || mouseY >= scaleY(955)) return -1;
        int row = (int) ((mouseY - top) / rowHeight);
        return row < Math.min(MAX_VISIBLE_MESSAGES, messageCount() - listOffset) ? row : -1;
    }

    private boolean inputBoundsContains(double mouseX, double mouseY) {
        return mouseX >= scaleX(300) && mouseX < scaleX(1330)
                && mouseY >= scaleY(902) && mouseY < scaleY(967);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingThumb && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            int travel = Math.max(1, scaleY(THUMB_TRAVEL));
            int maxOffset = maxListOffset();
            listOffset = maxOffset == 0 ? 0 : Math.max(0, Math.min(maxOffset,
                    dragStartOffset + (int) Math.round((event.y() - dragStartY) * maxOffset / travel)));
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    private void jumpToScrollPosition(double mouseY) {
        int maxOffset = maxListOffset();
        if (maxOffset == 0) {
            listOffset = 0;
            return;
        }
        double trackStart = scaleY(THUMB_BOUNDS[1]);
        double travel = Math.max(1.0D, scaleY(THUMB_TRAVEL));
        double position = Math.max(0.0D, Math.min(1.0D, (mouseY - trackStart) / travel));
        listOffset = (int) Math.round(position * maxOffset);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            double mouseX = event.x();
            double mouseY = event.y();
            boolean handled = pressedControl >= 0 || draggingThumb;
            int released = pressedControl;
            pressedControl = -1;
            draggingThumb = false;
            if (released == 2 && contains(WINDOW_BOUNDS[2], mouseX, mouseY)) onClose();
            return handled || super.mouseReleased(event);
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        scrollList(verticalAmount < 0 ? 1 : -1);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        StringBuilder input = drafts.get(activeTab);
        int keyCode = event.key();
        if (keyCode == 256) {
            onClose();
            return true;
        }
        if (keyCode == 257 || keyCode == 335) {
            submitInput();
            return true;
        }
        if (keyCode == 259 && !input.isEmpty()) {
            input.deleteCharAt(input.length() - 1);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        StringBuilder input = drafts.get(activeTab);
        int codePoint = event.codepoint();
        if (!Character.isISOControl(codePoint) && input.length() < 160) {
            input.appendCodePoint(codePoint);
            return true;
        }
        return super.charTyped(event);
    }

    private void submitInput() {
        if (activeTab == 1) return;
        StringBuilder input = drafts.get(activeTab);
        if (input.isEmpty()) return;
        conversations.get(activeTab).add(input.toString());
        input.setLength(0);
        listOffset = maxListOffset();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(null);
    }

    @Override
    public void removed() {
        if (mapConsumerAcquired && SimuMapManager.isAvailable()) {
            mapManager.releaseConsumer();
            mapConsumerAcquired = false;
        }
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
