package myau.management;

import myau.access.AccessorPlayerControllerMP;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.UpdateEvent;
import myau.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C09PacketHeldItemChange;

public final class SlotComponent {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int HOTBAR_CONTAINER_OFFSET = 36;
    private static int spoofedSlot = -1;
    private static boolean spoofing = false;

    public static int getSlot() {
        if (mc.thePlayer == null) {
            return 0;
        }
        return spoofing ? spoofedSlot : mc.thePlayer.inventory.currentItem;
    }

    public static int getRealSlot() {
        return mc.thePlayer == null ? 0 : mc.thePlayer.inventory.currentItem;
    }

    public static ItemStack getItemStack() {
        if (mc.thePlayer == null || mc.thePlayer.inventoryContainer == null) {
            return null;
        }
        return mc.thePlayer.inventoryContainer.getSlot(getSlot() + HOTBAR_CONTAINER_OFFSET).getStack();
    }

    public static Item getItem() {
        ItemStack stack = getItemStack();
        return stack == null ? null : stack.getItem();
    }

    public static boolean isSpoofing() {
        return spoofing;
    }

    public static void setSlot(int slot) {
        if (slot < 0 || slot > 8 || mc.thePlayer == null || mc.playerController == null) {
            return;
        }
        spoofedSlot = slot;
        spoofing = true;
        sync();
    }

    public static void reset() {
        spoofing = false;
        spoofedSlot = mc.thePlayer == null ? -1 : mc.thePlayer.inventory.currentItem;
    }

    private static void sync() {
        if (AccessorPlayerControllerMP.getCurrentPlayerItem(mc.playerController) == spoofedSlot) {
            return;
        }
        AccessorPlayerControllerMP.setCurrentPlayerItem(mc.playerController, spoofedSlot);
        PacketUtil.sendPacket(new C09PacketHeldItemChange(spoofedSlot));
    }

    @EventTarget(Priority.LOWEST)
    public void onUpdate(UpdateEvent event) {
        if (event.getType() != EventType.PRE) {
            return;
        }
        reset();
    }
}
