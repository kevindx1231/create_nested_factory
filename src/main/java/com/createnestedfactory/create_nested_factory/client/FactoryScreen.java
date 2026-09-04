package com.createnestedfactory.create_nested_factory.client;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.createnestedfactory.create_nested_factory.block.OverclockTier;
import com.createnestedfactory.create_nested_factory.block.PortMode;
import com.createnestedfactory.create_nested_factory.menu.FactoryMenu;
import com.createnestedfactory.create_nested_factory.network.RenameFactoryPayload;
import com.simibubi.create.content.trains.station.NoShadowFontWrapper;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
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
 *  【当前布局】
 *    1. 左侧：最多六行输入和输出资源摘要
 *    2. 中部：名称、运行模式文字和六面端口按钮
 *    3. 底部：确认按钮点击热区
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

    // 纹理实际尺寸，右侧区域存放端口、按下态和改名图标素材。
    private static final int TEXTURE_WIDTH = 360;
    private static final int TEXTURE_HEIGHT = 219;
    // 顶部主体保持 342x152，窗口向下扩展以容纳玩家背包。
    private static final int BG_WIDTH = 342;
    private static final int BG_HEIGHT = 219;
    private static final int TOP_PANEL_HEIGHT = 152;
    private static final int INVENTORY_PANEL_X = 85;
    private static final int INVENTORY_PANEL_WIDTH = 172;
    private static final int HEADER_X = 84;
    private static final int HEADER_Y = 25;
    private static final int HEADER_WIDTH = 173;
    private static final int RESOURCE_ROWS = 6;
    private static final int RESOURCE_ROW_HEIGHT = 20;
    private static final int INPUT_ICON_X = 8;
    private static final int OUTPUT_ICON_X = 266;
    private static final int MODE_BUTTON_X = 179;
    private static final int MODE_BUTTON_Y = 46;
    private EditBox addressBox;   // 顶部改名输入框
    private AbstractButton[] faceButtons; // 6 个面的端口模式按钮
    private AbstractButton modeButton; // 黑盒/常加载切换按钮（透明热区）
    private AbstractButton destroyButton; // 左下：销毁图标的透明点击热区
    private AbstractButton confirmButton; // 右下：关闭图标的透明点击热区
    private OverclockSlider overclockSlider;

    public FactoryScreen(FactoryMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Override
    protected void init() {
        // 整体窗口包含顶部工厂面板和下方玩家背包。
        setWindowSize(BG_WIDTH, BG_HEIGHT);
        super.init();
        clearWidgets();

        // x / y = 界面窗口左上角。下面所有控件都用「x + 偏移、y + 偏移」定位。
        int x = leftPos;
        int y = topPos;

        // —— 顶部改名输入框（整体下移 3 像素）——
        addressBox = new EditBox(new NoShadowFontWrapper(font), x + HEADER_X, y + HEADER_Y,
                HEADER_WIDTH, font.lineHeight, Component.empty());
        addressBox.setBordered(false);      // 去掉输入框自带的边框（改回 true 可显示边框）
        addressBox.setMaxLength(25);        // 名字最长 25 个字符
        addressBox.setTextColor(0x3D3C48);  // 文字颜色（十六进制，可改）
        // 已有名字就显示、没有就留空（留空时会显示默认名作占位）
        addressBox.setValue(title.getContents() instanceof TranslatableContents ? "" : title.getString());
        addressBox.setFocused(false);
        addressBox.mouseClicked(0, 0, 0);
        // 输入文字时让输入框始终居中（跟着文字长度重新定位）
        addressBox.setResponder(s -> {
            addressBox.setX(nameBoxX(s, addressBox));
            sendRename();
        });
        addressBox.setX(nameBoxX(addressBox.getValue(), addressBox));
        addRenderableWidget(addressBox);

        // —— 6 个面的端口模式按钮（中心左上角 = 216,59）——
        // 索引顺序必须与 FactoryMenu.faceForButton 一致：0=正面, 1=上, 2=下, 3=左, 4=右, 5=后。
        int[][] facePositions = {
                {216, 59}, // 中心（正面，朝北）
                {216, 41}, // 上
                {216, 77}, // 下
                {198, 59}, // 左
                {234, 59}, // 右
                {234, 41}, // 后
        };
        faceButtons = new AbstractButton[6];
        for (int i = 0; i < 6; i++) {
            faceButtons[i] = new FaceButton(x + facePositions[i][0], y + facePositions[i][1], i);
            addRenderableWidget(faceButtons[i]);
        }

        // —— 切换模式按钮：位于两段模式文字之间的 7x8 间隙 ——
        modeButton = new ModeToggleButton(x + MODE_BUTTON_X, y + MODE_BUTTON_Y);
        addRenderableWidget(modeButton);

        // —— 左下：销毁按钮，点击热区为 90,105 到 108,123 ——
        destroyButton = new DestroyButton(x + 90, y + 105);
        addRenderableWidget(destroyButton);

        // —— 右下：关闭按钮，点击热区为 234,105 到 252,123 ——
        confirmButton = new ConfirmButton(x + 234, y + 105);
        addRenderableWidget(confirmButton);

        overclockSlider = new OverclockSlider(x + 90, y + 87);
        addRenderableWidget(overclockSlider);
    }

    // 计算名称文字在顶部区域里水平居中的 x 坐标。
    private int nameBoxX(String s, EditBox nameBox) {
        int textWidth = Math.min(font.width(s), nameBox.getWidth());
        return leftPos + HEADER_X + Math.max(0, (HEADER_WIDTH - textWidth) / 2);
    }

    private void sendRename() {
        if (minecraft != null && addressBox != null) {
            PacketDistributor.sendToServer(new RenameFactoryPayload(menu.containerId, addressBox.getValue()));
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;

        // 顶部主体整块绘制；底部只补中间背包面板，避开右侧和底部的滑块素材。
        graphics.blit(GUI_TEXTURE, x, y, 0, 0, BG_WIDTH, TOP_PANEL_HEIGHT,
                TEXTURE_WIDTH, TEXTURE_HEIGHT);
        graphics.blit(GUI_TEXTURE, x + INVENTORY_PANEL_X, y + TOP_PANEL_HEIGHT,
                INVENTORY_PANEL_X, TOP_PANEL_HEIGHT, INVENTORY_PANEL_WIDTH,
                BG_HEIGHT - TOP_PANEL_HEIGHT, TEXTURE_WIDTH, TEXTURE_HEIGHT);

        // —— 顶部改名栏的占位文字 + 铅笔图标 ——
        // 未聚焦时显示：空则画默认名，铅笔画在名称右侧 7px。
        String text = addressBox.getValue();
        if (!addressBox.isFocused()) {
            if (text.isEmpty()) {
                text = title.getString();
                int nameX = nameBoxX(text, addressBox);
                graphics.drawString(font, text, nameX, y + HEADER_Y, 0x3D3C48, false);
            }
            // 铅笔素材位于纹理 351,98，编辑聚焦时隐藏。
            int nameX = nameBoxX(text, addressBox);
            graphics.blit(GUI_TEXTURE, nameX + font.width(text) + 7, y + HEADER_Y,
                    351, 98, 9, 9, TEXTURE_WIDTH, TEXTURE_HEIGHT);
        }

        // —— 运行模式标签：向右、向下各移动 1 像素 ——
        Component runModeLabel = Component.translatable("gui.create_nested_factory.run_mode");
        int labelX = x + 92 + (34 - font.width(runModeLabel)) / 2;
        graphics.drawString(font, runModeLabel, labelX, y + 46, 0x3D3C48, false);

        // —— 当前模式文字：向左、向下各移动 1 像素 ——
        Component modeText = Component.translatable("gui.create_nested_factory.mode." + menu.getMode().getSerializedName());
        graphics.drawString(font, modeText, x + 134, y + 46, 0x3D3C48, false);

        // —— 最多六行实时输入/输出 ——
        for (int i = 0; i < RESOURCE_ROWS; i++) {
            int rowY = y + 20 + i * RESOURCE_ROW_HEIGHT;
            renderRate(graphics, x + INPUT_ICON_X, rowY, x + 25, rowY + 3,
                    menu.getInputType(i), menu.getInputId(i), menu.getInputRate(i));
            renderRate(graphics, x + OUTPUT_ICON_X, rowY, x + 283, rowY + 3,
                    menu.getOutputType(i), menu.getOutputId(i), menu.getOutputRate(i));
        }
    }

    // 渲染某方向的物品/流体图标（type: 0无/1物品/2流体）+ 速率文字。
    private void renderRate(GuiGraphics graphics, int iconX, int iconY, int textX, int textY, int type, int id, float rate) {
        if (type == 1) {
            Item item = BuiltInRegistries.ITEM.byId(id);
            if (item != Items.AIR) {
                graphics.renderItem(new ItemStack(item), iconX, iconY);
            }
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
        sendRename();
        super.removed();
    }

    // 6 个面的端口模式按钮：素材列从纹理 x=342 开始，每个状态高 18。
    private class FaceButton extends AbstractButton {
        private final int faceIndex;
        private boolean pressed;

        FaceButton(int x, int y, int faceIndex) {
            super(x, y, 18, 18, Component.empty());
            this.faceIndex = faceIndex;
        }

        @Override
        public void onPress() {
            if (minecraft != null && minecraft.gameMode != null) {
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, faceIndex);
            }
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
            // 非中心面：无/输入/输出 = y 0/18/36。
            // 中心面：无不叠加图标，输入/输出 = y 54/72。
            int v;
            if (mode == PortMode.NONE) {
                v = center ? -1 : 0;
            } else if (mode == PortMode.INPUT) {
                v = center ? 54 : 18;
            } else {
                v = center ? 72 : 36;
            }
            if (pressed) {
                graphics.setColor(0.65f, 0.78f, 1.0f, 1.0f); // 按下变淡蓝
            }
            if (v >= 0) {
                graphics.blit(GUI_TEXTURE, getX(), getY(), 342, v, 18, 18, TEXTURE_WIDTH, TEXTURE_HEIGHT);
            }
            graphics.setColor(1.0f, 1.0f, 1.0f, 1.0f);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        }
    }

    // 确认图标已经在主体背景中，只保留点击区域，并复用原图做按下着色反馈。
    private class ConfirmButton extends AbstractButton {
        private boolean pressed;

        ConfirmButton(int x, int y) {
            super(x, y, 18, 18, Component.empty());
        }

        @Override
        public void onPress() {
            sendRename();
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.closeContainer();
            }
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (button != 0 || !active || !visible || !isMouseOver(mx, my)) {
                return false;
            }
            pressed = true;
            setFocused(true);
            if (minecraft != null) {
                playDownSound(minecraft.getSoundManager());
            }
            return true;
        }

        @Override
        public boolean mouseReleased(double mx, double my, int button) {
            if (button != 0 || !pressed) {
                return false;
            }
            boolean releaseInside = isMouseOver(mx, my);
            pressed = false;
            setFocused(false);
            if (releaseInside) {
                onPress();
            }
            return true;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (pressed) {
                graphics.setColor(0.65f, 0.78f, 1.0f, 1.0f);
                graphics.blit(GUI_TEXTURE, getX(), getY(), 234, 105, 18, 18,
                        TEXTURE_WIDTH, TEXTURE_HEIGHT);
                graphics.setColor(1.0f, 1.0f, 1.0f, 1.0f);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        }
    }

    private class DestroyButton extends AbstractButton {
        private boolean pressed;

        DestroyButton(int x, int y) {
            super(x, y, 18, 18, Component.empty());
        }

        @Override
        public void onPress() {
            sendRename();
            if (minecraft != null && minecraft.gameMode != null) {
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, FactoryMenu.DESTROY_BUTTON_ID);
            }
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (button != 0 || !active || !visible || !isMouseOver(mx, my)) {
                return false;
            }
            pressed = true;
            setFocused(true);
            if (minecraft != null) {
                playDownSound(minecraft.getSoundManager());
            }
            return true;
        }

        @Override
        public boolean mouseReleased(double mx, double my, int button) {
            if (button != 0 || !pressed) {
                return false;
            }
            boolean releaseInside = isMouseOver(mx, my);
            pressed = false;
            setFocused(false);
            if (releaseInside) {
                onPress();
            }
            return true;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (pressed) {
                graphics.setColor(0.65f, 0.78f, 1.0f, 1.0f);
                graphics.blit(GUI_TEXTURE, getX(), getY(), 90, 105, 18, 18,
                        TEXTURE_WIDTH, TEXTURE_HEIGHT);
                graphics.setColor(1.0f, 1.0f, 1.0f, 1.0f);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        }
    }

    private class OverclockSlider extends AbstractButton {
        private static final int TRACK_WIDTH = 99;
        private static final int KNOB_WIDTH = 5;
        private static final int KNOB_HEIGHT = 9;
        private static final int KNOB_MIN_X = 0;
        private static final int KNOB_NORMAL_X = 26;
        private static final int KNOB_TRIPLE_X = 52;
        private static final int KNOB_QUADRUPLE_X = 73;
        private static final int KNOB_QUINTUPLE_X = 94;

        private boolean dragging;
        private int knobOffset = KNOB_NORMAL_X;
        private OverclockTier pendingTier;
        private int pendingFrames;

        OverclockSlider(int x, int y) {
            super(x, y, TRACK_WIDTH, KNOB_HEIGHT, Component.empty());
        }

        @Override
        public void onPress() {
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            syncFromMenu();
            if (button != 0 || !menu.isOverclockEnabled()
                    || mouseX < getX() || mouseX >= getX() + TRACK_WIDTH
                    || mouseY < getY() - 2 || mouseY >= getY() + KNOB_HEIGHT + 2) {
                return false;
            }
            dragging = true;
            setFocused(true);
            updateDragPosition(mouseX);
            return true;
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
            if (!dragging || button != 0) {
                return false;
            }
            updateDragPosition(mouseX);
            return true;
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            if (!dragging || button != 0) {
                return false;
            }
            updateDragPosition(mouseX);
            OverclockTier target = nearestUnlockedTier(knobOffset);
            knobOffset = offsetForTier(target);
            dragging = false;
            setFocused(false);
            if (minecraft != null) {
                playDownSound(minecraft.getSoundManager());
                if (minecraft.gameMode != null && target != menu.getSelectedOverclockTier()) {
                    pendingTier = target;
                    pendingFrames = 20;
                    minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 20 + target.id());
                }
            }
            return true;
        }

        private void updateDragPosition(double mouseX) {
            int maxOffset = maximumOffset(menu.getOverclockBatteryCount());
            knobOffset = Mth.clamp((int) Math.round(mouseX - getX() - KNOB_WIDTH / 2.0),
                    KNOB_MIN_X, maxOffset);
        }

        private void syncFromMenu() {
            if (dragging) {
                return;
            }
            int batteries = menu.getOverclockBatteryCount();
            if (pendingTier != null) {
                if (menu.getSelectedOverclockTier() == pendingTier) {
                    pendingTier = null;
                } else if (pendingFrames-- > 0 && pendingTier.unlockedBy(batteries)
                        && menu.isOverclockEnabled()) {
                    knobOffset = offsetForTier(pendingTier);
                    return;
                } else {
                    pendingTier = null;
                }
            }
            knobOffset = batteries <= 0 ? KNOB_NORMAL_X : offsetForTier(menu.getSelectedOverclockTier());
        }

        private OverclockTier nearestUnlockedTier(int offset) {
            OverclockTier closest = OverclockTier.HALF;
            int closestDistance = Integer.MAX_VALUE;
            OverclockTier[] candidates = {
                    OverclockTier.HALF,
                    OverclockTier.DOUBLE,
                    OverclockTier.TRIPLE,
                    OverclockTier.QUADRUPLE,
                    OverclockTier.QUINTUPLE
            };
            int batteries = menu.getOverclockBatteryCount();
            for (OverclockTier tier : candidates) {
                if (!tier.unlockedBy(batteries)) {
                    continue;
                }
                int distance = Math.abs(offset - offsetForTier(tier));
                if (distance < closestDistance || distance == closestDistance
                        && tier.multiplier() > closest.multiplier()) {
                    closest = tier;
                    closestDistance = distance;
                }
            }
            return closest;
        }

        private int maximumOffset(int batteries) {
            return offsetForTier(OverclockTier.highestForBatteries(batteries));
        }

        private int offsetForTier(OverclockTier tier) {
            return switch (tier) {
                case HALF -> KNOB_MIN_X;
                case NORMAL, DOUBLE -> KNOB_NORMAL_X;
                case TRIPLE -> KNOB_TRIPLE_X;
                case QUADRUPLE -> KNOB_QUADRUPLE_X;
                case QUINTUPLE -> KNOB_QUINTUPLE_X;
            };
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            syncFromMenu();
            boolean enabled = menu.isOverclockEnabled();
            active = enabled;
            if (enabled && knobOffset > 0) {
                graphics.blit(GUI_TEXTURE, getX(), getY() + 2, 261, 160,
                        knobOffset == KNOB_QUINTUPLE_X ? TRACK_WIDTH : knobOffset, 5,
                        TEXTURE_WIDTH, TEXTURE_HEIGHT);
            }
            graphics.blit(GUI_TEXTURE, getX() + knobOffset, getY(),
                    dragging ? 345 : enabled ? 355 : 350, 151, KNOB_WIDTH, KNOB_HEIGHT,
                    TEXTURE_WIDTH, TEXTURE_HEIGHT);
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
            if (minecraft != null && minecraft.gameMode != null) {
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 6);
            }
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (button != 0 || !active || !visible || !isMouseOver(mx, my)) {
                return false;
            }
            pressed = true;
            setFocused(true);
            if (minecraft != null) {
                playDownSound(minecraft.getSoundManager());
            }
            return true;
        }

        @Override
        public boolean mouseReleased(double mx, double my, int button) {
            if (button != 0 || !pressed) {
                return false;
            }
            boolean releaseInside = isMouseOver(mx, my);
            pressed = false;
            setFocused(false);
            if (releaseInside) {
                onPress();
            }
            return true;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (pressed) {
                graphics.blit(GUI_TEXTURE, getX(), getY(), 352, 90, 7, 8, TEXTURE_WIDTH, TEXTURE_HEIGHT);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        }
    }
}
