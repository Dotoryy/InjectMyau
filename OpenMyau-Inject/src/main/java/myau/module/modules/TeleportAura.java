package myau.module.modules;

import myau.Myau;
import myau.access.AccessorPlayerControllerMP;
import myau.access.AccessorRenderManager;
import myau.event.EventManager;
import myau.event.EventTarget;
import myau.events.AttackEvent;
import myau.events.PlayerUpdateEvent;
import myau.events.Render3DEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.PacketUtil;
import myau.util.RandomUtil;
import myau.util.RenderUtil;
import myau.util.TeamUtil;
import myau.util.TimerUtil;
import myau.util.path.TeleportPath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.monster.EntitySilverfish;
import net.minecraft.entity.monster.EntitySlime;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityBat;
import net.minecraft.entity.passive.EntitySquid;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemHoe;
import net.minecraft.item.ItemPickaxe;
import net.minecraft.item.ItemSpade;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.potion.Potion;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class TeleportAura extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int MODE_MULTIPLE = 1;
    private static final double DEFAULT_ATTACK_SPEED = 4.0;

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"SINGLE", "MULTIPLE"});
    public final FloatProperty range = new FloatProperty("range", 32.0F, 3.0F, 100.0F);
    public final IntProperty minCPS = new IntProperty("min-cps", 10, 1, 20, () -> !this.cooldown.getValue());
    public final IntProperty maxCPS = new IntProperty("max-cps", 15, 1, 20, () -> !this.cooldown.getValue());
    public final BooleanProperty cooldown = new BooleanProperty("cooldown-1.9", false);
    public final BooleanProperty render = new BooleanProperty("render", true);

    private final TimerUtil clickTimer = new TimerUtil();
    private long nextSwing = 0L;
    private EntityLivingBase target = null;
    private List<Vec3> path = null;

    public TeleportAura() {
        super("Teleport Aura", false);
    }

    public EntityLivingBase getTarget() {
        return this.target;
    }

    @Override
    public void onDisabled() {
        this.target = null;
        this.path = null;
    }

    @EventTarget
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        List<EntityLivingBase> targets = this.collectTargets();
        if (targets.isEmpty()) {
            this.target = null;
            return;
        }
        targets.sort(Comparator.comparingDouble(entity -> mc.thePlayer.getDistanceToEntity(entity)));
        this.target = targets.get(0);
        if (!mc.thePlayer.isDead) {
            this.doAttack(targets);
        }
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || !this.render.getValue() || this.path == null || this.target == null) {
            return;
        }
        Vec3 previous = null;
        for (Vec3 point : this.path) {
            if (previous != null) {
                Vec3 start = new Vec3(
                        previous.xCoord - AccessorRenderManager.getRenderPosX(mc.getRenderManager()),
                        previous.yCoord + 0.01 - AccessorRenderManager.getRenderPosY(mc.getRenderManager()),
                        previous.zCoord - AccessorRenderManager.getRenderPosZ(mc.getRenderManager())
                );
                RenderUtil.drawLine3D(start, point.xCoord, point.yCoord + 0.01, point.zCoord,
                        1.0F, 1.0F, 1.0F, 1.0F, 1.0F);
            }
            previous = point;
        }
    }

    private List<EntityLivingBase> collectTargets() {
        List<EntityLivingBase> targets = new ArrayList<>();
        double range = this.range.getValue().doubleValue();
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!(entity instanceof EntityLivingBase)) {
                continue;
            }
            EntityLivingBase living = (EntityLivingBase) entity;
            if (!this.isValidTarget(living) || mc.thePlayer.getDistanceToEntity(living) > range) {
                continue;
            }
            targets.add(living);
        }
        return targets;
    }

    private boolean isValidTarget(EntityLivingBase entity) {
        if (entity == mc.thePlayer || entity == mc.thePlayer.ridingEntity || entity.deathTime > 0) {
            return false;
        }
        if (entity == mc.getRenderViewEntity() || entity == mc.getRenderViewEntity().ridingEntity) {
            return false;
        }
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        if (killAura == null) {
            return false;
        }
        if (entity instanceof EntityOtherPlayerMP) {
            if (!killAura.players.getValue() || TeamUtil.isFriend((EntityPlayer) entity)) {
                return false;
            }
            return (!killAura.teams.getValue() || !TeamUtil.isSameTeam((EntityPlayer) entity))
                    && !TeamUtil.isBot((EntityPlayer) entity);
        }
        if (entity instanceof EntityDragon || entity instanceof EntityWither) {
            return killAura.bosses.getValue();
        }
        if (entity instanceof EntityMob || entity instanceof EntitySlime) {
            if (entity instanceof EntitySilverfish) {
                return killAura.silverfish.getValue()
                        && (!killAura.teams.getValue() || !TeamUtil.hasTeamColor(entity));
            }
            return killAura.mobs.getValue();
        }
        if (entity instanceof EntityAnimal
                || entity instanceof EntityBat
                || entity instanceof EntitySquid
                || entity instanceof EntityVillager) {
            return killAura.animals.getValue();
        }
        if (entity instanceof EntityIronGolem) {
            return killAura.golems.getValue()
                    && (!killAura.teams.getValue() || !TeamUtil.hasTeamColor(entity));
        }
        return false;
    }

    private void doAttack(List<EntityLivingBase> targets) {
        boolean ready;
        if (this.cooldown.getValue()) {
            double speed = this.getAttackSpeed();
            double delay = 1.0 / speed * 20.0 - 1.0;
            ready = this.clickTimer.hasTimeElapsed((long) (delay * 50.0));
        } else {
            ready = this.clickTimer.hasTimeElapsed(this.nextSwing);
        }
        if (!ready || this.target == null
                || mc.gameSettings.keyBindAttack.isKeyDown()
                || mc.gameSettings.keyBindUseItem.isKeyDown()) {
            return;
        }
        if (!this.cooldown.getValue()) {
            long cps = RandomUtil.nextLong(
                    this.minCPS.getValue().longValue(), this.maxCPS.getValue().longValue());
            this.nextSwing = 1000L / Math.max(1L, cps);
        }
        double range = this.range.getValue().doubleValue();
        if (this.mode.getValue() == MODE_MULTIPLE) {
            for (EntityLivingBase entity : targets) {
                if (mc.thePlayer.getDistanceToEntity(entity) <= range) {
                    this.attack(entity);
                }
            }
        } else if (mc.thePlayer.getDistanceToEntity(this.target) <= range) {
            this.attack(this.target);
        }
        this.clickTimer.reset();
    }

    private double getAttackSpeed() {
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null) {
            return DEFAULT_ATTACK_SPEED;
        }
        Item item = held.getItem();
        if (item instanceof ItemSword) {
            return 1.6;
        }
        if (item instanceof ItemSpade) {
            return 1.0;
        }
        if (item instanceof ItemPickaxe) {
            return 1.2;
        }
        if (item instanceof ItemAxe) {
            switch (((ItemAxe) item).getToolMaterial()) {
                case WOOD:
                case STONE:
                    return 0.8;
                case IRON:
                    return 0.9;
                default:
                    return 1.0;
            }
        }
        if (item instanceof ItemHoe) {
            String material = ((ItemHoe) item).getMaterialName();
            if ("STONE".equals(material)) {
                return 2.0;
            }
            if ("IRON".equals(material)) {
                return 3.0;
            }
            if ("WOOD".equals(material) || "GOLD".equals(material)) {
                return 1.0;
            }
            return DEFAULT_ATTACK_SPEED;
        }
        return DEFAULT_ATTACK_SPEED;
    }

    private void attack(EntityLivingBase entity) {
        AccessorPlayerControllerMP.callSyncCurrentPlayItem(mc.playerController);
        AttackEvent event = new AttackEvent(entity);
        EventManager.call(event);
        if (event.isCancelled()) {
            return;
        }
        List<Vec3> route = TeleportPath.build(
                new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ),
                new Vec3(entity.posX, entity.posY, entity.posZ),
                true
        );
        if (route == null || route.isEmpty()) {
            return;
        }
        this.path = route;
        for (Vec3 point : route) {
            PacketUtil.sendPacketNoEvent(
                    new C03PacketPlayer.C04PacketPlayerPosition(point.xCoord, point.yCoord, point.zCoord, true));
        }
        mc.thePlayer.swingItem();
        PacketUtil.sendPacketNoEvent(new C02PacketUseEntity(entity, C02PacketUseEntity.Action.ATTACK));
        List<Vec3> back = new ArrayList<>(route);
        Collections.reverse(back);
        for (Vec3 point : back) {
            PacketUtil.sendPacketNoEvent(
                    new C03PacketPlayer.C04PacketPlayerPosition(point.xCoord, point.yCoord, point.zCoord, true));
        }
        if (mc.thePlayer.fallDistance > 0.0F
                && !mc.thePlayer.onGround
                && !mc.thePlayer.isOnLadder()
                && !mc.thePlayer.isInWater()
                && !mc.thePlayer.isPotionActive(Potion.blindness)
                && mc.thePlayer.ridingEntity == null) {
            mc.thePlayer.onCriticalHit(entity);
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getValue() == MODE_MULTIPLE ? "Multiple" : "Single"};
    }
}
