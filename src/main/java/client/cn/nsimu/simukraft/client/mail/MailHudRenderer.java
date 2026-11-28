package client.cn.nsimu.simukraft.client.mail;

import common.cn.nsimu.simukraft.SimuKraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 在游戏 HUD 左上角绘制邮件图标及未读提示动画。
 */
public final class MailHudRenderer {
    private static final Identifier READ_ICON = Identifier.fromNamespaceAndPath(
            SimuKraft.MOD_ID, "textures/gui/mail.png");
    private static final Identifier UNREAD_ICON = Identifier.fromNamespaceAndPath(
            SimuKraft.MOD_ID, "textures/gui/incoming_mail.png");
    static final int ICON_WIDTH = 26;
    static final int ICON_HEIGHT = 21;
    static final int MARGIN = 5;

    private MailHudRenderer() {
    }

    public static void render(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        // 教程界面需要透过遮罩露出 HUD 上的邮箱，其余界面打开时不画。
        if (minecraft.player == null || (minecraft.gui.screen() != null && !(minecraft.gui.screen() instanceof MailTutorialScreen))) {
            return;
        }
        drawIcon(event.getGuiGraphics(), MailClientState.hasUnread());
    }

    /**
     * 画左上角邮箱图标。教程界面在遮罩之后会再调一次，并固定画来信图标，
     * 保证聚光灯里露出的是“收到邮件”的样子，而不是被黑色遮罩压暗或被换成普通图标。
     */
    static void drawIcon(GuiGraphicsExtractor graphics, boolean unread) {
        int x = MARGIN + Math.round(MailClientState.shakeOffset(System.currentTimeMillis()));
        int y = MARGIN;
        Identifier icon = unread ? UNREAD_ICON : READ_ICON;
        graphics.blit(RenderPipelines.GUI_TEXTURED, icon, x, y, 0.0F, 0.0F,
                ICON_WIDTH, ICON_HEIGHT, ICON_WIDTH, ICON_HEIGHT, ICON_WIDTH, ICON_HEIGHT);
    }
}
