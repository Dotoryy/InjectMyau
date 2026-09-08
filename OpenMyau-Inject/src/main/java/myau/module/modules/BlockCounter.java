package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.events.Render2DEvent;
import myau.module.Module;
import myau.util.BlockUtil;
import myau.util.ItemUtil;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import org.lwjgl.opengl.GL11;

import java.awt.Color;

public class BlockCounter extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public BlockCounter() {
        super("Block Counter", false);
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (!ItemUtil.isHoldingBlock()) {
            return;
        }
        Scaffold scaffold = (Scaffold) Myau.moduleManager.modules.get(Scaffold.class);
        if (scaffold != null && scaffold.isEnabled() && scaffold.blockCounter.getValue()) {
            return;
        }

        int count = 0;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (stack == null || stack.stackSize <= 0) {
                continue;
            }
            Item item = stack.getItem();
            if (!(item instanceof ItemBlock)) {
                continue;
            }
            Block block = ((ItemBlock) item).getBlock();
            if (!BlockUtil.isInteractable(block) && BlockUtil.isSolid(block)) {
                count += stack.stackSize;
            }
        }

        HUD hud = (HUD) Myau.moduleManager.modules.get(HUD.class);
        float scale = hud.scale.getValue();
        ScaledResolution resolution = new ScaledResolution(mc);
        GlStateManager.pushMatrix();
        GlStateManager.scale(scale, scale, 0.0F);
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        mc.fontRendererObj.drawString(
                String.format("%d block%s left", count, count != 1 ? "s" : ""),
                ((float) resolution.getScaledWidth() / 2.0F
                        + (float) mc.fontRendererObj.FONT_HEIGHT * 1.5F) / scale,
                (float) resolution.getScaledHeight() / 2.0F / scale
                        - (float) mc.fontRendererObj.FONT_HEIGHT / 2.0F + 1.0F,
                (count > 0 ? Color.WHITE.getRGB() : new Color(255, 85, 85).getRGB()) | -1090519040,
                hud.shadow.getValue());
        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }
}
