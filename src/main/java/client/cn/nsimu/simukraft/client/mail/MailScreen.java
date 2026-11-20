package client.cn.nsimu.simukraft.client.mail;

import com.mojang.blaze3d.platform.InputConstants;
import common.cn.nsimu.simukraft.SimuKraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** 只绘制邮件背景图的全屏界面，打开时邮件已由服务端确认已读。 */
public final class MailScreen extends Screen {
    private static final Identifier BACKGROUND = Identifier.fromNamespaceAndPath(
            SimuKraft.MOD_ID, "textures/gui/mail_background.png");
    private static final int BACKGROUND_WIDTH = 1920;
    private static final int BACKGROUND_HEIGHT = 1080;
    private static final int BUTTON_SIZE = 53;
    private static final WindowButton MINIMIZE = new WindowButton(
            1723, 24, "textures/gui/mail_minimize.png", "textures/gui/mail_unpressed_minimize.png");
    private static final WindowButton WINDOWED = new WindowButton(
            1781, 24, "textures/gui/mail_windowed.png", "textures/gui/mail_unpressed_windowed.png");
    private static final WindowButton CLOSE = new WindowButton(
            1842, 23, "textures/gui/mail_close.png", "textures/gui/mail_unpressed_close.png");

    private WindowButton pressedButton;

    public MailScreen() {
        super(Component.empty());
    }

    /** 禁用原版菜单背景，避免对邮件背景图应用模糊或暗色遮罩。 */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    }

    /** 将邮件背景图直接拉伸到整个 GUI，不叠加任何控件或遮罩。 */
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, 0, 0, 0.0F, 0.0F, width, height,
                BACKGROUND_WIDTH, BACKGROUND_HEIGHT, BACKGROUND_WIDTH, BACKGROUND_HEIGHT);
        drawWindowButton(graphics, MINIMIZE);
        drawWindowButton(graphics, WINDOWED);
        drawWindowButton(graphics, CLOSE);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawWindowButton(GuiGraphicsExtractor graphics, WindowButton button) {
        int x = scaleX(button.x());
        int y = scaleY(button.y());
        int sizeX = scaleX(BUTTON_SIZE);
        int sizeY = scaleY(BUTTON_SIZE);
        Identifier texture = button == pressedButton ? button.pressed() : button.unpressed();
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0.0F, 0.0F, sizeX, sizeY,
                BUTTON_SIZE, BUTTON_SIZE, BUTTON_SIZE, BUTTON_SIZE);
    }

    private int scaleX(int sourceX) {
        return Math.round(sourceX * (float) width / BACKGROUND_WIDTH);
    }

    private int scaleY(int sourceY) {
        return Math.round(sourceY * (float) height / BACKGROUND_HEIGHT);
    }

    private WindowButton buttonAt(double mouseX, double mouseY) {
        if (contains(MINIMIZE, mouseX, mouseY)) {
            return MINIMIZE;
        }
        if (contains(WINDOWED, mouseX, mouseY)) {
            return WINDOWED;
        }
        if (contains(CLOSE, mouseX, mouseY)) {
            return CLOSE;
        }
        return null;
    }

    private boolean contains(WindowButton button, double mouseX, double mouseY) {
        int x = scaleX(button.x());
        int y = scaleY(button.y());
        return mouseX >= x && mouseX < x + scaleX(BUTTON_SIZE)
                && mouseY >= y && mouseY < y + scaleY(BUTTON_SIZE);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            WindowButton target = buttonAt(event.x(), event.y());
            if (target != null) {
                pressedButton = target;
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && pressedButton != null) {
            WindowButton releasedButton = pressedButton;
            pressedButton = null;
            if (releasedButton == CLOSE && contains(CLOSE, event.x(), event.y())) {
                onClose();
            }
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record WindowButton(int x, int y, Identifier pressed, Identifier unpressed) {
        private WindowButton(int x, int y, String pressed, String unpressed) {
            this(x, y,
                    Identifier.fromNamespaceAndPath(SimuKraft.MOD_ID, pressed),
                    Identifier.fromNamespaceAndPath(SimuKraft.MOD_ID, unpressed));
        }
    }
}
