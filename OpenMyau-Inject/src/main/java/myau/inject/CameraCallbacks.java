package myau.inject;

import myau.Myau;
import myau.access.AccessorEntityLivingBase;
import myau.access.AccessorEntityPlayer;
import myau.event.EventManager;
import myau.events.PickEvent;
import myau.events.RaytraceEvent;
import myau.module.modules.AntiDebuff;
import myau.module.modules.GhostHand;
import myau.module.modules.KillAura;
import myau.module.modules.NoHurtCam;
import myau.module.modules.Scaffold;
import myau.module.modules.ViewClip;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.util.Vec3;

import java.util.List;

public final class CameraCallbacks {
    public static final String OWNER = "myau/inject/CameraCallbacks";
    private static Integer savedSlot;
    private static ItemStack savedUsing;
    private static boolean usingSaved;
    private static Integer savedUseCount;
    private static Integer rendererSlot;
    private CameraCallbacks() {
    }

    public static void cameraPre() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.thePlayer == null || Myau.moduleManager == null) {
                return;
            }
            Scaffold scaffold = (Scaffold) Myau.moduleManager.modules.get(Scaffold.class);
            if (scaffold.isEnabled() && scaffold.itemSpoof.getValue()) {
                int slot = scaffold.getSlot();
                if (slot >= 0) {
                    savedSlot = Integer.valueOf(mc.thePlayer.inventory.currentItem);
                    mc.thePlayer.inventory.currentItem = slot;
                }
            }
            KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
            if (killAura.isEnabled() && killAura.isBlocking()) {
                savedUsing = AccessorEntityPlayer.getItemInUse(mc.thePlayer);
                usingSaved = true;
                AccessorEntityPlayer.setItemInUse(mc.thePlayer,
                        mc.thePlayer.inventory.getCurrentItem());
                savedUseCount = Integer.valueOf(
                        AccessorEntityPlayer.getItemInUseCount(mc.thePlayer));
                AccessorEntityPlayer.setItemInUseCount(mc.thePlayer, 69000);
            }
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
    }
    public static void cameraPost() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.thePlayer == null) {
                return;
            }
            if (savedSlot != null) {
                mc.thePlayer.inventory.currentItem = savedSlot.intValue();
            }
            if (usingSaved) {
                AccessorEntityPlayer.setItemInUse(mc.thePlayer, savedUsing);
            }
            if (savedUseCount != null) {
                AccessorEntityPlayer.setItemInUseCount(mc.thePlayer, savedUseCount.intValue());
            }
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        } finally {
            savedSlot = null;
            savedUsing = null;
            usingSaved = false;
            savedUseCount = null;
        }
    }
    public static void rendererPre() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.thePlayer == null || Myau.moduleManager == null) {
                return;
            }
            Scaffold scaffold = (Scaffold) Myau.moduleManager.modules.get(Scaffold.class);
            if (scaffold.isEnabled() && scaffold.itemSpoof.getValue()) {
                int slot = scaffold.getSlot();
                if (slot >= 0) {
                    rendererSlot = Integer.valueOf(mc.thePlayer.inventory.currentItem);
                    mc.thePlayer.inventory.currentItem = slot;
                }
            }
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
    }
    public static void rendererPost() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (rendererSlot != null && mc.thePlayer != null) {
                mc.thePlayer.inventory.currentItem = rendererSlot.intValue();
            }
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        } finally {
            rendererSlot = null;
        }
    }
    public static float hurtCameraAngle(float value) {
        try {
            if (Myau.moduleManager != null) {
                NoHurtCam noHurtCam = (NoHurtCam) Myau.moduleManager.modules.get(NoHurtCam.class);
                if (noHurtCam.isEnabled()) {
                    return value * (float) noHurtCam.multiplier.getValue().intValue() / 100.0F;
                }
            }
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
        return value;
    }
    public static double pickRange(double range) {
        try {
            PickEvent event = new PickEvent(range);
            EventManager.call(event);
            return event.getRange();
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
            return range;
        }
    }
    public static double raytraceRange(double range) {
        try {
            RaytraceEvent event = new RaytraceEvent(range);
            EventManager.call(event);
            return event.getRange();
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
            return range;
        }
    }
    public static int entityCandidates(List<Entity> candidates) {
        try {
            if (Myau.moduleManager != null) {
                GhostHand ghostHand = (GhostHand) Myau.moduleManager.modules.get(GhostHand.class);
                if (ghostHand.isEnabled()) {
                    candidates.removeIf(ghostHand::shouldSkip);
                }
            }
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
        return candidates.size();
    }
    public static double cameraDistance(Vec3 from, Vec3 to) {
        try {
            if (Myau.moduleManager != null
                    && Myau.moduleManager.modules.get(ViewClip.class).isEnabled()) {
                return myau.access.AccessorEntityRenderer.getThirdPersonDistance(
                        Minecraft.getMinecraft().entityRenderer);
            }
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
        return from.distanceTo(to);
    }
    public static Material fogMaterial(Block block) {
        try {
            if (Myau.moduleManager != null
                    && Myau.moduleManager.modules.get(ViewClip.class).isEnabled()) {
                return Material.air;
            }
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
        return block.getMaterial();
    }
    public static boolean blindnessForFog(EntityLivingBase entity, Potion potion) {
        return potionActive(entity, potion, true);
    }

    public static boolean nauseaForCamera(EntityPlayerSP player, Potion potion) {
        return potionActive(player, potion, false);
    }
    private static boolean potionActive(EntityLivingBase entity, Potion potion,
                                        boolean blindness) {
        try {
            Potion hidden = blindness ? Potion.blindness : Potion.confusion;
            if (potion == hidden && Myau.moduleManager != null) {
                AntiDebuff antiDebuff = (AntiDebuff) Myau.moduleManager.modules.get(AntiDebuff.class);
                boolean off = blindness
                        ? antiDebuff.blindness.getValue().booleanValue()
                        : antiDebuff.nausea.getValue().booleanValue();
                if (antiDebuff.isEnabled() && off) {
                    return false;
                }
            }
            java.util.Map effects = AccessorEntityLivingBase.getActivePotionsMap(entity);
            return effects != null && effects.containsKey(Integer.valueOf(potion.id));
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
            return false;
        }
    }
}
