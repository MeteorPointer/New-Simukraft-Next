package client.cn.nsimu.simukraft.client.ui;

import com.lowdragmc.lowdraglib2.gui.texture.*;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 圆角半透明面板底色。26.3 的渲染管线改动较大，画面截取模糊暂不可用，
 * 元素底色退化为圆角半透明填充，直接绘制退化为半透明矩形。只影响本模组界面。
 */
public final class SimuKraftGlassTextures {
    static final float PANEL_RADIUS = 14.0F;
    static final float CARD_RADIUS = 8.0F;
    private static final int PANEL_ALPHA = 0x66;
    private static final int CARD_ALPHA = 0x4A;
    private static final int BORDER = 0x331C2A40;

    private SimuKraftGlassTextures() {
    }

    /**
     * enabled: 读取客户端配置，判断是否启用毛玻璃底色。
     */
    public static boolean enabled() {
        return false;
    }

    /**
     * panel: 窗口和主面板底色。
     */
    public static IGuiTexture panel(int fallbackColor, int fallbackBorder) {
        if (!enabled()) {
            return new ColorRectTexture(fallbackColor);
        }
        return layered(PANEL_RADIUS, tint(PANEL_ALPHA), BORDER, 1.0F);
    }

    /**
     * card: 卡片和次级面板底色。
     */
    public static IGuiTexture card(int fallbackColor, int fallbackBorder) {
        if (!enabled()) {
            return new ColorRectTexture(fallbackColor);
        }
        return layered(CARD_RADIUS, tint(CARD_ALPHA), BORDER, 1.0F);
    }

    /**
     * fill: 在 GuiGraphicsExtractor 上绘制面板底。26.3 无直接圆角绘制接口，退化为半透明矩形填充。
     */
    public static void fill(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color) {
        if (width <= 0 || height <= 0) {
            return;
        }
        graphics.fill(x, y, x + width, y + height, color);
    }

    /**
     * applyElement: 把已有矩形底色换成圆角半透明底，保留物品图标等非底色层。
     */
    public static void applyElement(UIElement element) {
        if (!enabled() || element == null || element.getStyle() == null) {
            return;
        }
        IGuiTexture texture = element.getStyle().backgroundTexture();
        IGuiTexture replaced = replace(texture, role(element));
        if (replaced != texture) {
            element.style(style -> style.backgroundTexture(replaced));
        }
    }

    private static IGuiTexture replace(IGuiTexture texture, Role role) {
        if (texture == null || texture == IGuiTexture.EMPTY) {
            return texture;
        }
        if (texture instanceof ColorRectTexture) {
            return layered(role.radius, tint(role == Role.PANEL ? PANEL_ALPHA : CARD_ALPHA), BORDER, role.stroke);
        }
        if (texture instanceof GuiTextureGroup group) {
            IGuiTexture[] textures = group.getTextures();
            if (textures == null || textures.length == 0) {
                return texture;
            }
            IGuiTexture[] replaced = new IGuiTexture[textures.length];
            boolean changed = false;
            for (int index = 0; index < textures.length; index++) {
                replaced[index] = replaceLayer(textures[index], role);
                changed |= replaced[index] != textures[index];
            }
            return changed ? new GuiTextureGroup(replaced) : texture;
        }
        if (texture instanceof SpriteTexture && role != Role.CONTENT) {
            return layered(role.radius, tint(PANEL_ALPHA), BORDER, role.stroke);
        }
        return texture;
    }

    private static IGuiTexture replaceLayer(IGuiTexture texture, Role role) {
        if (texture instanceof ColorRectTexture || texture instanceof SpriteTexture) {
            return replace(texture, role);
        }
        return texture;
    }

    private static Role role(UIElement element) {
        if (element.hasClass("simukraft_card_content_panel") || element.hasClass("simukraft_card_slot")
                || element.hasClass("simukraft_card_shadow") || element.hasClass("simukraft_grid_panel")
                || element.hasClass("simukraft_badge")) {
            return Role.CARD;
        }
        return Role.PANEL;
    }

    private static int tint(int alpha) {
        return (alpha << 24) | 0x101828;
    }

    private static IGuiTexture layered(float radius, int tint, int border, float stroke) {
        return new SDFRectTexture().setRadius(radius).setStroke(stroke).setColor(tint).setBorderColor(border);
    }

    /**
     * captureBackdrop: 26.3 不做画面截取模糊，保留空实现供各界面调用。
     */
    public static void captureBackdrop() {
    }

    /**
     * releaseBackdrop: 26.3 无底图缓存，保留空实现供界面关闭时调用。
     */
    public static void releaseBackdrop() {
    }

    private enum Role {
        PANEL(PANEL_RADIUS, 1.0F),
        CARD(CARD_RADIUS, 1.0F),
        CONTENT(0.0F, 0.0F);

        private final float radius;
        private final float stroke;

        Role(float radius, float stroke) {
            this.radius = radius;
            this.stroke = stroke;
        }
    }
}
