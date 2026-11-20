package client.cn.nsimu.simukraft.client.mail;

import common.cn.nsimu.simukraft.SimuKraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/** 在游戏 HUD 左上角绘制邮件图标及未读提示动画。 */
public final class MailHudRenderer {
    private static final Identifier READ_ICON = Identifier.fromNamespaceAndPath(
            SimuKraft.MOD_ID, "textures/gui/mail.png");
    private static final Identifier UNREAD_ICON = Identifier.fromNamespaceAndPath(
            SimuKraft.MOD_ID, "textures/gui/incoming_mail.png");
    private static final int ICON_WIDTH = 26;
    private static final int ICON_HEIGHT = 21;
    private static final int MARGIN = 5;

    private MailHudRenderer() {
    }

    public static void render(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.gui.screen() != null) {
            return;
        }
        GuiGraphicsExtractor graphics = event.getGuiGraphics();
        int x = MARGIN + Math.round(MailClientState.shakeOffset(System.currentTimeMillis()));
        int y = MARGIN;
        Identifier icon = MailClientState.hasUnread() ? UNREAD_ICON : READ_ICON;
        graphics.blit(RenderPipelines.GUI_TEXTURED, icon, x, y, 0.0F, 0.0F,
                ICON_WIDTH, ICON_HEIGHT, ICON_WIDTH, ICON_HEIGHT, ICON_WIDTH, ICON_HEIGHT);
    }
}
