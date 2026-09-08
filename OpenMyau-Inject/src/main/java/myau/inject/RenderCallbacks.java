package myau.inject;

import myau.Myau;
import myau.event.EventManager;
import myau.event.types.EventType;
import myau.events.RenderLivingEvent;
import myau.management.RotationState;
import myau.module.modules.AntiObfuscate;
import myau.module.modules.ESP;
import myau.module.modules.NameTags;
import myau.module.modules.NickHider;
import myau.module.modules.Sprint;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.attributes.IAttributeInstance;

public final class RenderCallbacks {
    public static final String OWNER = "myau/inject/RenderCallbacks";
    private RenderCallbacks() {
    }

    public static int itemInUseCountForRender(net.minecraft.entity.player.EntityPlayer player) {
        try {
            int actual = player.getItemInUseCount();
            if (actual > 0 || !myau.util.ReflectionUtils.isItemInUse()) {
                return actual;
            }
            net.minecraft.item.ItemStack held = player.getHeldItem();
            if (held != null
                    && held.getItemUseAction() == net.minecraft.item.EnumAction.BLOCK) {
                return 1;
            }
            return actual;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static String text(String text) {
        try {
            if (Myau.moduleManager == null) {
                return text;
            }
            AntiObfuscate antiObfuscate =
                    (AntiObfuscate) Myau.moduleManager.modules.get(AntiObfuscate.class);
            if (antiObfuscate.isEnabled()) {
                text = antiObfuscate.stripObfuscated(text);
            }
            NickHider nickHider = (NickHider) Myau.moduleManager.modules.get(NickHider.class);
            return nickHider.isEnabled() ? nickHider.replaceNick(text) : text;
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
            return text;
        }
    }
    public static char formattingCode(String text, int index) {
        char c = text.charAt(index);
        return c != '0' && c != '1' && c != '2' && c != '3' && c != '4'
                && c != '5' && c != '6' && c != '7' && c != '8' && c != '9'
                && c != 'a' && c != 'A' && c != 'b' && c != 'B' && c != 'c' && c != 'C'
                && c != 'd' && c != 'D' && c != 'e' && c != 'E' && c != 'f' && c != 'F'
                ? c : 'r';
    }
    private static float prevRenderYawOffset;
    private static float renderYawOffset;
    private static float prevRotationYawHead;
    private static float rotationYawHead;
    private static float prevRotationPitch;
    private static float rotationPitch;
    private static EntityPlayerSP swapped;
    public static void renderEntityStaticPre(Entity entity) {
        try {
            swapped = null;
            if (!(entity instanceof EntityPlayerSP) || !RotationState.isRotated(1)) {
                return;
            }
            EntityPlayerSP player = (EntityPlayerSP) entity;
            prevRenderYawOffset = player.prevRenderYawOffset;
            renderYawOffset = player.renderYawOffset;
            prevRotationYawHead = player.prevRotationYawHead;
            rotationYawHead = player.rotationYawHead;
            prevRotationPitch = player.prevRotationPitch;
            rotationPitch = player.rotationPitch;
            player.prevRenderYawOffset = RotationState.getPrevRenderYawOffset();
            player.renderYawOffset = RotationState.getRenderYawOffset();
            player.prevRotationYawHead = RotationState.getPrevRotationYawHead();
            player.rotationYawHead = RotationState.getRotationYawHead();
            player.prevRotationPitch = RotationState.getPrevRotationPitch();
            player.rotationPitch = RotationState.getRotationPitch();
            swapped = player;
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
    }
    public static void renderEntityStaticPost() {
        try {
            EntityPlayerSP player = swapped;
            if (player == null) {
                return;
            }
            player.prevRenderYawOffset = prevRenderYawOffset;
            player.renderYawOffset = renderYawOffset;
            player.prevRotationYawHead = prevRotationYawHead;
            player.rotationYawHead = rotationYawHead;
            player.prevRotationPitch = prevRotationPitch;
            player.rotationPitch = rotationPitch;
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        } finally {
            swapped = null;
        }
    }

    private static EntityLivingBase rendering;
    public static void renderLivingPre(EntityLivingBase entity) {
        rendering = entity;
        try {
            EventManager.call(new RenderLivingEvent(EventType.PRE, entity));
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
    }
    public static void renderLivingPost() {
        EntityLivingBase entity = rendering;
        try {
            if (entity != null) {
                EventManager.call(new RenderLivingEvent(EventType.POST, entity));
            }
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
    }
    public static Object canRenderName(EntityLivingBase entity) {
        try {
            if (Myau.moduleManager == null) {
                return null;
            }
            NameTags nameTags = (NameTags) Myau.moduleManager.modules.get(NameTags.class);
            if (nameTags.isEnabled() && nameTags.shouldRenderTags(entity)) {
                return Boolean.FALSE;
            }
            ESP esp = (ESP) Myau.moduleManager.modules.get(ESP.class);
            if (esp.isEnabled() && !esp.isOutlineEnabled()) {
                return Boolean.FALSE;
            }
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
        return null;
    }

    private static Object fovOwner;
    public static void fovModifierPre(Object owner) {
        fovOwner = owner;
    }

    public static double fovAttributeValue(IAttributeInstance attribute) {
        double value = attribute.getAttributeValue();
        try {
            if (fovOwner instanceof EntityPlayerSP && Myau.moduleManager != null) {
                Sprint sprint = (Sprint) Myau.moduleManager.modules.get(Sprint.class);
                if (sprint.isEnabled() && sprint.shouldApplyFovFix(attribute)) {
                    return value * 1.300000011920929;
                }
            }
        } catch (Throwable swallowed) {
            Log.swallowed(swallowed);
        }
        return value;
    }
}
