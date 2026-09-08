package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.Render2DEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.PercentProperty;
import myau.util.BlockUtil;
import net.minecraft.block.BlockLiquid;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.BlockPos;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MathHelper;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

public class FallView extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final float MINIMUM_FALL = 2.5F;
    private static final float DISTANCE_COLOUR_MAX = 20.0F;
    private static final int MAX_CACHE_SIZE = 1000;
    private static final int CACHE_TRIM_SIZE = 500;
    private static final int ENCHANTMENT_SAMPLES = 100;

    public final PercentProperty damageThreshold = new PercentProperty("damage-threshold", 0);
    public final BooleanProperty disableWhileFlying = new BooleanProperty("disable-while-flying", true);
    public final BooleanProperty onlyWhileSneaking = new BooleanProperty("only-while-sneaking", false);
    public final BooleanProperty overrideHealthFormat =
            new BooleanProperty("override-health-format", true);
    public final BooleanProperty heartSymbol = new BooleanProperty("heart-symbol", true);
    public final BooleanProperty showDamage = new BooleanProperty("show-damage", true);
    public final BooleanProperty showDistance = new BooleanProperty("show-distance", false);
    public final BooleanProperty showRemainingHealth =
            new BooleanProperty("show-remaining-health", false);

    private double fallStartY = -1.0;
    private double groundY = -1.0;
    private float cachedFallDistance = 0.0F;
    private int cachedEnchantmentModifier = -1;
    private ItemStack[] cachedArmorInventory = new ItemStack[4];
    private boolean armorCacheValid = false;
    private final Map<DamageCacheKey, Integer> damageCache = new HashMap<DamageCacheKey, Integer>();

    private String damageText;
    private boolean showDamageText;
    private String distanceText;
    private int distanceTextColor = Color.WHITE.getRGB();
    private boolean showDistanceText;
    private boolean recalculateAfterTeleport;

    public FallView() {
        super("Fall View", false);
    }

    @Override
    public void onDisabled() {
        this.fallStartY = -1.0;
        this.groundY = -1.0;
        this.cachedFallDistance = 0.0F;
        this.cachedEnchantmentModifier = -1;
        this.cachedArmorInventory = new ItemStack[4];
        this.armorCacheValid = false;
        this.damageCache.clear();
        this.recalculateAfterTeleport = false;
        this.clearOverlayState();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.POST) {
            return;
        }
        this.clearOverlayState();
        if (mc.currentScreen != null || mc.thePlayer == null || mc.theWorld == null
                || mc.thePlayer.capabilities.isCreativeMode) {
            return;
        }
        if (this.recalculateAfterTeleport) {
            this.recalculateAfterTeleport = false;
            this.recalculateFallTracking();
        }
        if (this.disableWhileFlying.getValue() && mc.thePlayer.capabilities.allowFlying) {
            return;
        }
        if (this.onlyWhileSneaking.getValue() && !mc.thePlayer.isSneaking()) {
            return;
        }
        if (mc.thePlayer.onGround) {
            this.fallStartY = -1.0;
            this.groundY = -1.0;
            this.cachedFallDistance = 0.0F;
        } else if (this.fallStartY == -1.0) {
            this.fallStartY = mc.thePlayer.posY;
            this.groundY = this.findGroundY(mc.thePlayer.posX, mc.thePlayer.posZ);
        } else {
            double newGroundY = this.findGroundY(mc.thePlayer.posX, mc.thePlayer.posZ);
            if (newGroundY != this.groundY) {
                this.groundY = newGroundY;
                this.cachedFallDistance = 0.0F;
            }
        }

        float fallDistance = this.calculateFallDistance();
        if (fallDistance <= MINIMUM_FALL) {
            return;
        }

        PotionEffect jumpEffect = mc.thePlayer.getActivePotionEffect(Potion.jump);
        float jumpAmplifier = jumpEffect != null ? jumpEffect.getAmplifier() + 1 : 0.0F;
        int jumpBoostLevel = jumpEffect != null ? jumpEffect.getAmplifier() + 1 : 0;
        PotionEffect resistanceEffect = mc.thePlayer.getActivePotionEffect(Potion.resistance);
        boolean hasResistance = resistanceEffect != null;
        int resistanceLevel = hasResistance ? resistanceEffect.getAmplifier() + 1 : 0;

        ItemStack[] armorInventory = new ItemStack[4];
        boolean armorChanged = false;
        int armorHash = 0;
        for (int i = 0; i < 4; i++) {
            ItemStack currentArmor = mc.thePlayer.inventory.armorItemInSlot(i);
            armorInventory[i] = currentArmor;
            if (this.cachedArmorInventory[i] != currentArmor) {
                armorChanged = true;
            }
            if (currentArmor != null) {
                armorHash = armorHash * 31
                        + (currentArmor.getItem() != null ? currentArmor.getItem().hashCode() : 0);
                armorHash = armorHash * 31 + currentArmor.getItemDamage();
                armorHash = armorHash * 31 + EnchantmentHelper.getEnchantmentLevel(
                        Enchantment.featherFalling.effectId, currentArmor);
            }
        }

        int enchantmentModifier = this.cachedEnchantmentModifier;
        if (armorChanged || !this.armorCacheValid) {
            long totalModifier = 0L;
            for (int i = 0; i < ENCHANTMENT_SAMPLES; i++) {
                int modifier = EnchantmentHelper.getEnchantmentModifierDamage(
                        armorInventory, DamageSource.fall);
                if (modifier > 20) {
                    modifier = 20;
                }
                totalModifier += modifier;
            }
            enchantmentModifier = (int) Math.round((double) totalModifier / ENCHANTMENT_SAMPLES);
            this.cachedEnchantmentModifier = enchantmentModifier;
            System.arraycopy(armorInventory, 0, this.cachedArmorInventory, 0, 4);
            this.armorCacheValid = true;
            if (this.damageCache.size() > CACHE_TRIM_SIZE) {
                this.damageCache.clear();
            }
        }

        DamageCacheKey cacheKey =
                new DamageCacheKey(armorHash, fallDistance, jumpBoostLevel, resistanceLevel);
        Integer cachedFinalDamage = this.damageCache.get(cacheKey);
        int finalDamage;
        if (cachedFinalDamage != null) {
            finalDamage = cachedFinalDamage;
        } else {
            float damagePoints = fallDistance - 3.0F - jumpAmplifier;
            double damage = Math.max(0, MathHelper.ceiling_double_int(damagePoints));
            if (hasResistance && damage > 0.0) {
                damage = (25 - resistanceLevel * 5) * damage / 25.0;
            }
            if (damage > 0.0 && enchantmentModifier > 0) {
                damage = (25 - enchantmentModifier) * damage / 25.0;
            }
            finalDamage = MathHelper.ceiling_double_int(damage);
            if (this.damageCache.size() >= MAX_CACHE_SIZE) {
                this.damageCache.clear();
            }
            this.damageCache.put(cacheKey, finalDamage);
        }

        double currentHealth = mc.thePlayer.getHealth();
        double damagePercent = finalDamage / currentHealth * 100.0;
        if (this.showDamage.getValue() && finalDamage > 0
                && damagePercent > this.damageThreshold.getValue()) {
            double displayValue = this.showRemainingHealth.getValue()
                    ? Math.max(0.0, currentHealth - finalDamage)
                    : finalDamage;
            float hearts = (float) displayValue;
            if (this.overrideHealthFormat.getValue()) {
                hearts = (float) round(displayValue / 2.0, 1);
            }
            double percent = finalDamage / currentHealth;
            String colour = finalDamage >= currentHealth ? "§4"
                    : percent >= 0.7 ? "§c"
                    : percent >= 0.5 ? "§6"
                    : percent >= 0.3 ? "§e" : "§a";
            this.damageText = colour + asWholeNumber(hearts);
            if (this.heartSymbol.getValue()) {
                this.damageText = this.damageText + "§c❤§r";
            }
            this.showDamageText = true;
        }

        if (this.showDistance.getValue()) {
            this.distanceText = asWholeNumber(round(fallDistance, 2)) + "m";
            this.distanceTextColor = getDistanceColor(fallDistance).getRGB();
            this.showDistanceText = true;
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE) {
            return;
        }
        if (event.getPacket() instanceof S08PacketPlayerPosLook) {
            this.recalculateAfterTeleport = true;
            this.cachedFallDistance = 0.0F;
            this.clearOverlayState();
        }
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || mc.currentScreen != null || mc.thePlayer == null) {
            return;
        }
        if (!this.showDamageText && !this.showDistanceText) {
            return;
        }
        ScaledResolution resolution = new ScaledResolution(mc);
        if (this.showDamageText && this.damageText != null) {
            mc.fontRendererObj.drawStringWithShadow(this.damageText,
                    resolution.getScaledWidth() / 2.0F
                            - mc.fontRendererObj.getStringWidth(this.damageText) / 2.0F,
                    resolution.getScaledHeight() / 2.0F - 15.0F,
                    Color.WHITE.getRGB());
        }
        if (this.showDistanceText && this.distanceText != null) {
            mc.fontRendererObj.drawStringWithShadow(this.distanceText,
                    resolution.getScaledWidth() / 2.0F
                            - mc.fontRendererObj.getStringWidth(this.distanceText) / 2.0F,
                    resolution.getScaledHeight() / 2.0F + 6.0F,
                    this.distanceTextColor);
        }
    }

    private float calculateFallDistance() {
        if (this.fallStartY != -1.0 && this.groundY != -1.0) {
            if (this.cachedFallDistance == 0.0F) {
                this.cachedFallDistance = (float) Math.max(0.0, this.fallStartY - this.groundY);
            }
            return this.cachedFallDistance;
        }
        double ground = this.findGroundY(mc.thePlayer.posX, mc.thePlayer.posZ);
        return ground == -1.0 ? 0.0F : (float) Math.max(0.0, mc.thePlayer.posY - ground);
    }

    private double findGroundY(double x, double z) {
        int startY = (int) Math.floor(mc.thePlayer.posY);
        for (int y = startY; y > -1; y--) {
            BlockPos pos = new BlockPos(Math.floor(x), y, Math.floor(z));
            if (mc.theWorld.getBlockState(pos).getBlock() instanceof BlockLiquid) {
                return -1.0;
            }
            if (!isPassable(pos)) {
                return y + 1;
            }
        }
        return -1.0;
    }

    private static boolean isPassable(BlockPos pos) {
        return BlockUtil.isReplaceable(pos)
                || mc.theWorld.getBlockState(pos).getBlock() instanceof BlockLiquid;
    }

    private static Color getDistanceColor(float distance) {
        float normalized = MathHelper.clamp_float(
                (distance - MINIMUM_FALL) / (DISTANCE_COLOUR_MAX - MINIMUM_FALL), 0.0F, 1.0F);
        return new Color(255, (int) (255.0F * (1.0F - normalized)), 0);
    }

    private static double round(double value, int decimalPlaces) {
        if (decimalPlaces == 0) {
            return Math.round(value);
        }
        double power = Math.pow(10.0, decimalPlaces);
        return Math.round(value * power) / power;
    }

    private static String asWholeNumber(double input) {
        return input == Math.floor(input) && !Double.isInfinite(input)
                ? String.valueOf((int) input)
                : String.valueOf(input);
    }

    private void clearOverlayState() {
        this.damageText = null;
        this.showDamageText = false;
        this.distanceText = null;
        this.distanceTextColor = Color.WHITE.getRGB();
        this.showDistanceText = false;
    }

    private void recalculateFallTracking() {
        this.cachedFallDistance = 0.0F;
        this.damageCache.clear();
        this.clearOverlayState();
        if (mc.thePlayer.onGround) {
            this.fallStartY = -1.0;
            this.groundY = -1.0;
        } else {
            this.fallStartY = mc.thePlayer.posY;
            this.groundY = this.findGroundY(mc.thePlayer.posX, mc.thePlayer.posZ);
        }
    }

    private static final class DamageCacheKey {
        private final int armorHash;
        private final int fallDistanceInt;
        private final int jumpBoostLevel;
        private final int resistanceLevel;

        private DamageCacheKey(int armorHash, float fallDistance, int jumpBoost, int resistance) {
            this.armorHash = armorHash;
            this.fallDistanceInt = Math.round(fallDistance * 100.0F);
            this.jumpBoostLevel = jumpBoost;
            this.resistanceLevel = resistance;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (other == null || this.getClass() != other.getClass()) {
                return false;
            }
            DamageCacheKey that = (DamageCacheKey) other;
            return this.armorHash == that.armorHash
                    && this.fallDistanceInt == that.fallDistanceInt
                    && this.jumpBoostLevel == that.jumpBoostLevel
                    && this.resistanceLevel == that.resistanceLevel;
        }

        @Override
        public int hashCode() {
            return this.armorHash * 31 * 31 * 31
                    + this.fallDistanceInt * 31 * 31
                    + this.jumpBoostLevel * 31
                    + this.resistanceLevel;
        }
    }
}
