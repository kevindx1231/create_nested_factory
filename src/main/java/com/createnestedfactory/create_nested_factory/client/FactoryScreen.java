package com.createnestedfactory.create_nested_factory.client;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.createnestedfactory.create_nested_factory.block.PortMode;
import com.createnestedfactory.create_nested_factory.menu.FactoryMenu;
import com.createnestedfactory.create_nested_factory.network.RenameFactoryPayload;
import com.simibubi.create.content.trains.station.NoShadowFontWrapper;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import com.simibubi.create.foundation.gui.widget.IconButton;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/*
 * ============================================================
 *  嵌套工厂界面 —— 布局说明 / 调位置速查（新手看这里）
 * ============================================================
 *
 *  【坐标系 · 最重要】
 *    - leftPos / topPos = 「整个界面窗口」的左上角，由游戏自动算好并居中，
 *      你不需要改它们，只需要写「相对它们的偏移量」。
 *    - 本文件里出现的 x / y 几乎都是相对 leftPos / topPos 的偏移：
 *        x 越大越靠右，y 越大越靠下。
 *    - 整个窗口的宽高 imageWidth / imageHeight 在 init() 里用
 *      setWindowSize(宽, 高) 设置，想改整体大小就改那里。
 *
 *  【现在界面只剩两块】
 *    1. 顶部：改名输入框 + 铅笔图标 + 顶部装饰条（FROGPORT_HEADER）
 *    2. 底部一行：模式切换按钮 + 当前模式文字 + 打勾确认按钮
 *    （缓存格子、分页书签、玩家背包都已移除）
 *
 *  【常见调整速查】
 *    - 面板变宽 / 变窄：改下面的 BG_WIDTH
 *    - 面板变高 / 变矮：改下面的 BG_HEIGHT
 *    - 某个按钮左右移：改它坐标里的「x + 数字」
 *    - 某个按钮上下移：改它坐标里的「y + 数字」
 * ============================================================
 */
public class FactoryScreen extends AbstractSimiContainerScreen<FactoryMenu> {

    // 界面背景图纹理。
    private static final ResourceLocation GUI_TEXTURE =
            ResourceLocation.parse(Create_nested_factory.MODID + ":textures/gui/nested_factory.png");

    // 纹理实际宽（背景图原始宽，含右侧图标素材区）。
    private static final int TEXTURE_WIDTH = 256;
    // 面板的宽（= 窗口显示宽，只显示主面板）。
    private static final int BG_WIDTH = 226;
    // 面板的高（= 背景图高）。
    private static final int BG_HEIGHT = 111;
    // 顶部改名区域的宽（名称文字居中于此区域）。
    private static final int HEADER_WIDTH = 226;
    private EditBox addressBox;   // 顶部改名输入框
    private AbstractButton[] faceButtons; // 6 个面的端口模式按钮
    private AbstractButton modeButton; // 黑盒/常加载切换按钮（透明热区）
    private IconButton confirmButton; // 底部：打勾确认按钮

    public FactoryScreen(FactoryMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Override
    protected void init() {
        // 整体窗口大小 = 面板宽 × 面板高（没有玩家背包了，所以只加面板本身）。
        setWindowSize(BG_WIDTH, BG_HEIGHT);
        super.init();
        clearWidgets();

        // x / y = 界面窗口左上角。下面所有控件都用「x + 偏移、y + 偏移」定位。
        int x = leftPos;
        int y = topPos;

        // —— 顶部改名输入框 ——
        // 文字位于 y+4，高度 = 文字高，点击区贴合文字。
        addressBox = new EditBox(new NoShadowFontWrapper(font), x, y + 4, HEADER_WIDTH, font.lineHeight, Component.empty());
        addressBox.setBordered(false);      // 去掉输入框自带的边框（改回 true 可显示边框）
        addressBox.setMaxLength(25);        // 名字最长 25 个字符
        addressBox.setTextColor(0x3D3C48);  // 文字颜色（十六进制，可改）
        // 已有名字就显示、没有就留空（留空时会显示默认名作占位）
        addressBox.setValue(title.getContents() instanceof TranslatableContents ? "" : title.getString());
        addressBox.setFocused(false);
        addressBox.mouseClicked(0, 0, 0);
        // 输入文字时让输入框始终居中（跟着文字长度重新定位）
        addressBox.setResponder(s -> addressBox.setX(nameBoxX(s, addressBox)));
        addressBox.setX(nameBoxX(addressBox.getValue(), addressBox));
        addRenderableWidget(addressBox);

        // —— 6 个面的端口模式按钮（十字布局，中心 = 正面·北）——
        // 索引顺序必须与 FactoryMenu.faceForButton 一致：0=正面, 1=上, 2=下, 3=左, 4=右, 5=后。
        int[][] facePositions = {
                {184, 39}, // 中心（正面，朝北）
                {184, 21}, // 上
                {184, 57}, // 下
                {166, 39}, // 左
                {202, 39}, // 右
                {202, 21}, // 后
        };
        faceButtons = new AbstractButton[6];
        for (int i = 0; i < 6; i++) {
            faceButtons[i] = new FaceButton(x + facePositions[i][0], y + facePositions[i][1], i);
            addRenderableWidget(faceButtons[i]);
        }

        // —— 黑盒/常加载切换按钮（透明热区，按下显示右下角纹理）——
        modeButton = new ModeToggleButton(x + 134, y + 25);
        addRenderableWidget(modeButton);

        // —— 底部：打勾确认按钮（同 frogport，点了就关闭界面）——
        // x + BG_WIDTH - 25：距面板右边缘 25px；y + BG_HEIGHT - 24：距底 24px。
        confirmButton = new IconButton(x + BG_WIDTH - 25, y + BG_HEIGHT - 24, AllIcons.I_CONFIRM);
        confirmButton.withCallback(() -> this.minecraft.player.closeContainer());
        addRenderableWidget(confirmButton);
    }

    // 计算名称文字在顶部区域里水平居中的 x 坐标。
    private int nameBoxX(String s, EditBox nameBox) {
        int textWidth = Math.min(font.width(s), nameBox.getWidth());
        return leftPos + (HEADER_WIDTH - textWidth) / 2;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;

        // —— 背景图（只画主面板 226 宽，不显示右侧图标素材区）——
        // 纹理, x, y, u, v, 宽, 高, 纹理总宽, 纹理总高。
        graphics.blit(GUI_TEXTURE, x, y, 0, 0, BG_WIDTH, BG_HEIGHT, TEXTURE_WIDTH, BG_HEIGHT);

        // —— 顶部改名栏的占位文字 + 铅笔图标 ——
        // 未聚焦时显示：空则画默认名（有内容由 EditBox 渲染），铅笔画在名称右侧 7px。
        String text = addressBox.getValue();
        if (!addressBox.isFocused()) {
            if (text.isEmpty()) {
                text = title.getString();
                int nameX = nameBoxX(text, addressBox);
                graphics.drawString(font, text, nameX, y + 4, 0x3D3C48, false);
            }
            // 铅笔图标：名称右侧 7px，距 GUI 顶端 3px（编辑聚焦时隐藏）。
            int nameX = nameBoxX(text, addressBox);
            graphics.blit(GUI_TEXTURE, nameX + font.width(text) + 7, y + 3, 240, 102, 9, 9, TEXTURE_WIDTH, BG_HEIGHT);
        }

        // —— 运行模式标签（14,24 区域 W35 内水平居中）+ 当前模式文字（58,24，靠左）——
        Component runModeLabel = Component.translatable("gui.create_nested_factory.run_mode");
        int labelX = x + 20 + (32 - font.width(runModeLabel)) / 2;
        graphics.drawString(font, runModeLabel, labelX, y + 25, 0x3D3C48, false);
        Component modeText = Component.translatable("gui.create_nested_factory.mode." + menu.getMode().getSerializedName());
        graphics.drawString(font, modeText, x + 63, y + 25, 0x3D3C48, false);

        // —— 输入/输出图标 + 实时速度 ——
        renderRate(graphics, x + 32, y + 41, x + 49, y + 46, menu.getInputType(), menu.getInputId(), menu.getInputRate());
        renderRate(graphics, x + 32, y + 60, x + 49, y + 65, menu.getOutputType(), menu.getOutputId(), menu.getOutputRate());
    }

    // 渲染某方向的物品/流体图标（type: 0无/1物品/2流体）+ 速率文字。
    private void renderRate(GuiGraphics graphics, int iconX, int iconY, int textX, int textY, int type, int id, float rate) {
        if (type == 1) {
            Item item = BuiltInRegistries.ITEM.byId(id);
            graphics.renderItem(new ItemStack(item), iconX, iconY);
            graphics.drawString(font, String.format(Locale.ROOT, "%.0f/s", rate), textX, textY, 0x3D3C48, false);
        } else if (type == 2) {
            Fluid fluid = BuiltInRegistries.FLUID.byId(id);
            Item bucket = fluid.getBucket();
            if (bucket != Items.AIR) {
                graphics.renderItem(new ItemStack(bucket), iconX, iconY);
            }
            graphics.drawString(font, String.format(Locale.ROOT, "%.0f mB/s", rate), textX, textY, 0x3D3C48, false);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (addressBox.isFocused()) {
            // 改名输入框获得焦点时：Esc / 回车 = 结束输入（收起输入框）。
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                addressBox.setFocused(false);
                return true;
            }
            return addressBox.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (addressBox.isFocused()) {
            return addressBox.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public void removed() {
        // 界面关闭时把改名结果发给服务器保存（frogport 也是关屏时才保存）。
        PacketDistributor.sendToServer(new RenameFactoryPayload(menu.containerId, addressBox.getValue()));
        super.removed();
    }

    // 6 个面的端口模式按钮：根据当前模式显示右侧图标，按下时变淡蓝。
    private class FaceButton extends AbstractButton {
        private final int faceIndex;
        private boolean pressed;

        FaceButton(int x, int y, int faceIndex) {
            super(x, y, 18, 18, Component.empty());
            this.faceIndex = faceIndex;
        }

        @Override
        public void onPress() {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, faceIndex);
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (isMouseOver(mx, my)) {
                pressed = true;
            }
            return super.mouseClicked(mx, my, button);
        }

        @Override
        public boolean mouseReleased(double mx, double my, int button) {
            pressed = false;
            return super.mouseReleased(mx, my, button);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            PortMode mode = menu.getFaceMode(faceIndex);
            boolean center = faceIndex == 0;
            // 中心面“无”不叠加图标（露出按钮底材）；其余从右侧素材区取图标：
            // 输入/输出/无 = y 2/20/38，中心面专属输入/输出 = y 54/72。
            int v;
            if (mode == PortMode.NONE) {
                v = center ? -1 : 36;
            } else if (mode == PortMode.INPUT) {
                v = center ? 54 : 0;
            } else {
                v = center ? 72 : 18;
            }
            if (pressed) {
                graphics.setColor(0.65f, 0.78f, 1.0f, 1.0f); // 按下变淡蓝
            }
            if (v >= 0) {
                graphics.blit(GUI_TEXTURE, getX(), getY(), 238, v, 18, 18, TEXTURE_WIDTH, BG_HEIGHT);
            }
            graphics.setColor(1.0f, 1.0f, 1.0f, 1.0f);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        }
    }

    // 黑盒/常加载切换按钮：透明热区，按下时显示右下角纹理。
    private class ModeToggleButton extends AbstractButton {
        private boolean pressed;

        ModeToggleButton(int x, int y) {
            super(x, y, 7, 8, Component.empty());
        }

        @Override
        public void onPress() {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 6);
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (isMouseOver(mx, my)) {
                pressed = true;
            }
            return super.mouseClicked(mx, my, button);
        }

        @Override
        public boolean mouseReleased(double mx, double my, int button) {
            pressed = false;
            return super.mouseReleased(mx, my, button);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (pressed) {
                graphics.blit(GUI_TEXTURE, getX(), getY(), 249, 103, 7, 8, TEXTURE_WIDTH, BG_HEIGHT);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        }
    }
}
