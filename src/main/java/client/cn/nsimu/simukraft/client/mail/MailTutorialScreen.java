package client.cn.nsimu.simukraft.client.mail;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/** 第一次收到邮件后弹出的教程：先用聚光灯指出 HUD 左上角的邮箱，结束后打开邮件界面继续讲解。 */
public final class MailTutorialScreen extends Screen {
    private static final int HOLE_PADDING = 3;
    private final MailTutorialOverlay overlay = new MailTutorialOverlay(List.of(
            new MailTutorialOverlay.Step("tutorial.simukraft.mail.hud.1", MailTutorialOverlay.Target.HUD_ICON),
            new MailTutorialOverlay.Step("tutorial.simukraft.mail.hud.2", MailTutorialOverlay.Target.HUD_ICON),
            new MailTutorialOverlay.Step("tutorial.simukraft.mail.hud.3", MailTutorialOverlay.Target.HUD_ICON)),
            target -> target == MailTutorialOverlay.Target.HUD_ICON
                    ? new int[]{MailHudRenderer.MARGIN - HOLE_PADDING, MailHudRenderer.MARGIN - HOLE_PADDING,
                    MailHudRenderer.ICON_WIDTH + HOLE_PADDING * 2, MailHudRenderer.ICON_HEIGHT + HOLE_PADDING * 2}
                    : null);

    public MailTutorialScreen() {
        super(Component.translatable("tutorial.simukraft.mail.title"));
    }

    /** 不画原版模糊背景，让 HUD 和世界透过遮罩显示。 */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        overlay.render(graphics, font, width, height);
        // 遮罩盖在 HUD 之上；这里在遮罩之后补画一次来信图标，聚光灯里就不会被黑色图层压暗。
        MailHudRenderer.drawIcon(graphics, true);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            skip();
            return true;
        }
        return advanceStep();
    }

    /** 左键或推进键：讲完 HUD 部分后进入邮件界面继续讲解。 */
    private boolean advanceStep() {
        if (overlay.advance()) {
            MailClientState.open(true);
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        if (keyCode == 256) {
            skip();
            return true;
        }
        if (keyCode == 32 || keyCode == 257 || keyCode == 335) {
            return advanceStep();
        }
        return super.keyPressed(event);
    }

    private void skip() {
        MailClientState.finishTutorial();
        super.onClose();
    }

    /** ESC 由 keyPressed 处理为跳过；这里只防止其他路径静默关闭后反复弹出。 */
    @Override
    public void onClose() {
        skip();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
