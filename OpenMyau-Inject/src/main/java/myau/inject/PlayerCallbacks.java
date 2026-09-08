package myau.inject;

import myau.Myau;
import myau.event.EventManager;
import myau.events.AttackEvent;
import myau.events.CancelUseEvent;
import myau.events.SwapItemEvent;
import myau.events.WindowClickEvent;
import myau.module.modules.KeepSprint;
import myau.module.modules.NickHider;
import myau.module.modules.Scaffold;
import myau.module.modules.Sprint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;

public final class PlayerCallbacks {
    public static final String OWNER = "myau/inject/PlayerCallbacks";
    private PlayerCallbacks() {
    }

    public static boolean attackEntity(Object target) {
        try {
            if (!(target instanceof Entity)) {
                return false;
            }
            AttackEvent event = new AttackEvent((Entity) target, true);
            EventManager.call(event);
            if (event.isCancelled()) {
                return true;
            }
            if (Myau.moduleManager != null) {
                KeepSprint keepSprint =
                        (KeepSprint) Myau.moduleManager.modules.get(KeepSprint.class);
                if (keepSprint != null) {
                    keepSprint.confirmAttack();
                }
            }
            return false;
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
            return false;
        }
    }

    public static Object windowClick(int windowId, int slot, int button, int mode) {
        try {
            WindowClickEvent event = new WindowClickEvent(windowId, slot, button, mode);
            EventManager.call(event);
            if (event.isCancelled()) {
                return new Object[]{null};
            }
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
        return null;
    }
    public static boolean onStoppedUsingItem() {
        try {
            CancelUseEvent event = new CancelUseEvent();
            EventManager.call(event);
            return event.isCancelled();
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
            return false;
        }
    }
    private static KeyBinding pressed;
    public static void isPressedPre(Object binding) {
        pressed = (KeyBinding) binding;
    }
    public static boolean isPressed(boolean value) {
        try {
            if (!value || pressed == null) {
                return value;
            }
            Minecraft mc = Minecraft.getMinecraft();
            for (int i = 0; i < 9; i++) {
                if (mc.gameSettings.keyBindsHotbar[i].getKeyDescription()
                        .equals(pressed.getKeyDescription())) {
                    SwapItemEvent event = new SwapItemEvent(i, 0);
                    EventManager.call(event);
                    if (event.isCancelled()) {
                        return false;
                    }
                }
            }
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
        return value;
    }

    public static ItemStack heldItemForDisplay(InventoryPlayer inventory) {
        try {
            if (Myau.moduleManager != null) {
                Scaffold scaffold = (Scaffold) Myau.moduleManager.modules.get(Scaffold.class);
                if (scaffold.isEnabled() && scaffold.itemSpoof.getValue()) {
                    int slot = scaffold.getSlot();
                    if (slot >= 0) {
                        return inventory.getStackInSlot(slot);
                    }
                }
            }
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
        return inventory.getCurrentItem();
    }
    public static float experience(EntityPlayerSP player) {
        try {
            if (Myau.moduleManager != null) {
                NickHider nickHider = (NickHider) Myau.moduleManager.modules.get(NickHider.class);
                if (nickHider.isEnabled() && nickHider.level.getValue()) {
                    return 0.0F;
                }
            }
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
        return player.experience;
    }
    public static int experienceLevel(EntityPlayerSP player) {
        try {
            if (Myau.moduleManager != null) {
                NickHider nickHider = (NickHider) Myau.moduleManager.modules.get(NickHider.class);
                if (nickHider.isEnabled() && nickHider.level.getValue()) {
                    return 0;
                }
            }
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
        return player.experienceLevel;
    }

    public static boolean fovSprinting(EntityPlayer player) {
        boolean sprinting = player.isSprinting();
        try {
            if (player instanceof EntityPlayerSP && Myau.moduleManager != null) {
                Sprint sprint = (Sprint) Myau.moduleManager.modules.get(Sprint.class);
                return sprint.isEnabled() && sprint.shouldKeepFov(sprinting) || sprinting;
            }
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
        return sprinting;
    }
}
