package myau.module.modules;

import myau.Myau;
import myau.access.AccessorEntityLivingBase;
import myau.access.AccessorKeyBinding;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LoadWorldEvent;
import myau.events.MoveEvent;
import myau.events.MoveInputEvent;
import myau.events.PacketEvent;
import myau.events.PlayerUpdateEvent;
import myau.events.SprintEvent;
import myau.events.StrafeEvent;
import myau.events.TickEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.ui.clickgui.ClickGui;
import myau.util.KeyBindUtil;
import myau.util.MoveUtil;
import myau.util.PacketUtil;
import myau.util.PlayerUtil;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.client.C00PacketKeepAlive;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.client.C16PacketClientStatus;
import net.minecraft.potion.Potion;
import net.minecraft.util.BlockPos;

import net.minecraft.util.Vec3;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class InventoryMove extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final int MODE_NORMAL = 0;
    private static final int MODE_BUFFER_ABUSE = 1;
    private static final int MODE_CANCEL = 2;
    private static final int MODE_GRIM = 3;
    private static final int MODE_GRIM_2 = 4;
    private static final int MODE_WATCHDOG = 5;
    private static final int MODE_WATCHDOG_2 = 6;
    private static final int MODE_WATCHDOG_3 = 7;
    private static final int CLICK_MODE_QUICK_MOVE = 1;
    private static final int CLICK_MODE_SWAP = 2;
    private static final int CLICK_MODE_THROW = 4;
    private static final int WATCHDOG_3_STOP_TICKS = 10;
    private static final int BLINK_QUEUE_LIMIT = 100;

    public final ModeProperty mode = new ModeProperty("mode", MODE_NORMAL,
            new String[]{"Normal", "Buffer Abuse", "Cancel", "Grim", "Grim 2", "Watchdog",
                    "Watchdog 2", "Watchdog 3"});

    public final IntProperty clicks = new IntProperty("clicks", 3, 2, 10,
            () -> this.mode.getValue() == MODE_BUFFER_ABUSE);
    public final IntProperty amount = new IntProperty("amount", 5, 1, 10,
            () -> this.mode.getValue() == MODE_BUFFER_ABUSE);

    public final IntProperty managerExtraSprintTicks = new IntProperty("extra-sprint-ticks", 9, 0, 20,
            () -> this.mode.getValue() == MODE_GRIM);

    public final BooleanProperty predictionMode = new BooleanProperty("prediction", false,
            () -> this.mode.getValue() == MODE_WATCHDOG);
    public final IntProperty ticks = new IntProperty("ticks", 1, 1, 20,
            () -> this.mode.getValue() == MODE_WATCHDOG);
    public final BooleanProperty measureChestOpen = new BooleanProperty("measure-chest-open", true,
            () -> this.mode.getValue() == MODE_WATCHDOG);
    public final BooleanProperty inventory = new BooleanProperty("inventory", true,
            this::isWatchdogFamily);
    public final BooleanProperty container = new BooleanProperty("container", true,
            this::isWatchdogFamily);
    public final BooleanProperty allowJumping = new BooleanProperty("allow-jumping", true,
            () -> this.mode.getValue() == MODE_WATCHDOG_3);
    public final BooleanProperty allowSprinting = new BooleanProperty("allow-sprinting", true,
            () -> this.mode.getValue() == MODE_WATCHDOG_3);

    private final ConcurrentLinkedQueue<Packet<?>> queuedPackets = new ConcurrentLinkedQueue<>();
    private boolean waitedTick = false;
    private boolean flushed = false;
    private int clickCount = 0;

    private boolean speedSuppressed = false;
    private boolean inputDelayPassed = false;
    private long inputBlockStart = 0L;
    public static boolean inventoryClicking = false;
    public static boolean chestOpenConfirmed = false;
    private int openSentTick = -1;
    private int openLatencyTicks = -1;
    private int chestOpenTick = -1;
    private BlockPos pendingChestPos;
    private boolean openPending = false;
    private boolean awaitingChestGui = false;

    private boolean sentWindowPacket = false;

    private final java.util.ArrayDeque<Packet<?>> blinkQueue = new java.util.ArrayDeque<Packet<?>>();
    private boolean shouldBlink = false;
    private boolean stopMovement = false;
    private int ticksSinceClick = 0;

    private int groundTicks = 0;

    private final Map<Entity, Boolean> everMoved = new WeakHashMap<>();

    public InventoryMove() {
        super("Inventory Move", false);
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }

    public boolean isGrimBypass() {
        return this.isEnabled() && this.mode.getValue() == MODE_GRIM;
    }

    public int getExtraSprintTicks() {
        return this.managerExtraSprintTicks.getValue();
    }

    @Override
    public void onDisabled() {
        this.queuedPackets.clear();
        this.waitedTick = false;
        this.flushed = false;
        this.clickCount = 0;
        inventoryClicking = false;
        chestOpenConfirmed = false;
        this.speedSuppressed = false;
        this.inputDelayPassed = false;
        this.inputBlockStart = 0L;
        this.groundTicks = 0;
        this.everMoved.clear();
        this.resetOpenMeasurement();
        this.resetWatchdogExtras();
        if (mc.currentScreen != null) {
            KeyBinding.unPressAllKeys();
        }
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.queuedPackets.clear();
        this.everMoved.clear();
        this.resetOpenMeasurement();
        this.blinkQueue.clear();
        this.shouldBlink = false;
        this.stopMovement = false;
        this.ticksSinceClick = 0;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }
        if (mc.thePlayer.onGround) {
            this.groundTicks++;
        } else {
            this.groundTicks = 0;
        }
        if (this.mode.getValue() == MODE_WATCHDOG) {
            this.trackMovedEntities();
            return;
        }
        if (this.mode.getValue() == MODE_WATCHDOG_2) {
            this.watchdog2Tick();
            return;
        }
        if (this.mode.getValue() == MODE_WATCHDOG_3) {
            this.watchdog3Tick();
            return;
        }
        if (!this.screenAllowsMovement()) {
            return;
        }

        int selected = this.mode.getValue();
        boolean needsScreen = selected == MODE_NORMAL || selected == MODE_GRIM
                || selected == MODE_GRIM_2;
        if (needsScreen && mc.currentScreen == null) {
            return;
        }
        this.updateMovementKeyStates();
    }

    @EventTarget
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (!this.isEnabled() || this.mode.getValue() != MODE_WATCHDOG || mc.thePlayer == null) {
            return;
        }
        if (mc.currentScreen != null && this.screenAllowsMovement() && this.screenAllowed()) {
            this.updateMovementKeyStates();
        }
        this.watchdogMotion();
    }

    @EventTarget
    public void onMove(MoveEvent event) {
        if (!this.isEnabled() || this.mode.getValue() != MODE_WATCHDOG || mc.thePlayer == null
                || !this.screenAllowed()) {
            return;
        }
        boolean containerBusy = mc.currentScreen instanceof GuiChest || inventoryClicking;
        if (containerBusy && !this.predictionMode.getValue() && !mc.thePlayer.onGround) {
            event.setPosX(0.0);
            event.setPosZ(0.0);
        }
    }

    private void trackMovedEntities() {
        if (mc.theWorld == null) {
            return;
        }
        for (Object raw : mc.theWorld.loadedEntityList) {
            if (!(raw instanceof Entity)) {
                continue;
            }
            Entity entity = (Entity) raw;
            if (Boolean.TRUE.equals(this.everMoved.get(entity))) {
                continue;
            }
            boolean movedThisTick = entity.getPositionVector().distanceTo(
                    new Vec3(entity.lastTickPosX, entity.lastTickPosY, entity.lastTickPosZ)) > 0.1
                    || (entity.ticksExisted > 10
                    && (Math.abs(entity.prevPosX - entity.posX) > 0.2
                    || Math.abs(entity.prevPosY - entity.posY) > 0.2
                    || Math.abs(entity.prevPosZ - entity.posZ) > 0.2));
            if (movedThisTick) {
                this.everMoved.put(entity, Boolean.TRUE);
            }
        }
    }

    public void updateMovementKeyStates() {
        this.update(mc.gameSettings.keyBindForward);
        this.update(mc.gameSettings.keyBindBack);
        this.update(mc.gameSettings.keyBindRight);
        this.update(mc.gameSettings.keyBindLeft);
        this.update(mc.gameSettings.keyBindJump);
    }

    private void update(KeyBinding keyBinding) {
        KeyBindUtil.updateKeyState(keyBinding.getKeyCode());
    }

    private boolean isWatchdogFamily() {
        int selected = this.mode.getValue();
        return selected == MODE_WATCHDOG || selected == MODE_WATCHDOG_2
                || selected == MODE_WATCHDOG_3;
    }

    private boolean screenAllowed() {
        if (!this.isWatchdogFamily()) {
            return true;
        }
        if (mc.currentScreen instanceof GuiInventory) {
            return this.inventory.getValue();
        }
        if (mc.currentScreen instanceof GuiChest) {
            return this.container.getValue();
        }
        return false;
    }

    private boolean screenAllowsMovement() {
        return !(mc.currentScreen instanceof GuiChat) && !(mc.currentScreen instanceof ClickGui);
    }

    @EventTarget
    public void onSprint(SprintEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        switch (this.mode.getValue()) {
            case MODE_GRIM:
            case MODE_GRIM_2:
                if (mc.currentScreen instanceof GuiInventory || mc.currentScreen instanceof GuiChest) {
                    mc.thePlayer.setSprinting(false);
                }
                break;
            case MODE_WATCHDOG:
                if (this.screenAllowed()
                        && (mc.currentScreen instanceof GuiChest || inventoryClicking)) {
                    mc.thePlayer.setSprinting(false);
                }
                break;
            default:
                break;
        }
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND) {
            return;
        }
        Packet<?> packet = event.getPacket();
        this.trackWindowPacket(packet);

        switch (this.mode.getValue()) {
            case MODE_BUFFER_ABUSE:
                if (packet instanceof C0EPacketClickWindow) {
                    if (this.isBuffering() && !this.flushed) {
                        event.setCancelled(true);
                        this.queuedPackets.add(packet);
                        return;
                    }
                    this.clickCount++;
                }
                break;
            case MODE_CANCEL:
                this.cancelOpen(event, packet);
                break;
            case MODE_WATCHDOG:
                this.watchdogPacket(event, packet);
                break;
            case MODE_WATCHDOG_2:
                this.watchdog2Packet(event, packet);
                break;
            case MODE_WATCHDOG_3:
                if (event.getType() == EventType.SEND
                        && packet instanceof C0EPacketClickWindow) {
                    this.stopMovement = true;
                    this.ticksSinceClick = 0;
                }
                break;
            default:
                break;
        }
    }

    private void trackWindowPacket(Packet<?> packet) {
        if (packet instanceof C03PacketPlayer) {
            this.sentWindowPacket = false;
            return;
        }
        if (packet instanceof C0EPacketClickWindow
                || packet instanceof C0DPacketCloseWindow
                || (packet instanceof C16PacketClientStatus
                && ((C16PacketClientStatus) packet).getStatus()
                == C16PacketClientStatus.EnumState.OPEN_INVENTORY_ACHIEVEMENT)) {
            this.sentWindowPacket = true;
        }
    }

    private void cancelOpen(PacketEvent event, Packet<?> packet) {
        if (packet instanceof C16PacketClientStatus
                && ((C16PacketClientStatus) packet).getStatus()
                == C16PacketClientStatus.EnumState.OPEN_INVENTORY_ACHIEVEMENT) {
            event.setCancelled(true);
            return;
        }
        if (packet instanceof C0BPacketEntityAction
                && ((C0BPacketEntityAction) packet).getAction()
                == C0BPacketEntityAction.Action.OPEN_INVENTORY) {
            event.setCancelled(true);
            return;
        }
        if (packet instanceof C0DPacketCloseWindow) {
            event.setCancelled(true);
        }
    }

    private void watchdogPacket(PacketEvent event, Packet<?> packet) {
        if (!event.isCancelled() && mc.thePlayer != null && mc.theWorld != null
                && this.measureChestOpen.getValue()) {
            if (packet instanceof C08PacketPlayerBlockPlacement) {
                BlockPos position = ((C08PacketPlayerBlockPlacement) packet).getPosition();
                if (!(mc.currentScreen instanceof GuiChest) && this.isChest(position)) {
                    this.pendingChestPos = position;
                    this.openPending = true;
                }
            } else if (packet instanceof C02PacketUseEntity) {
                C02PacketUseEntity use = (C02PacketUseEntity) packet;
                Entity entity = use.getEntityFromWorld(mc.theWorld);
                if (!(mc.currentScreen instanceof GuiChest)
                        && use.getAction() != C02PacketUseEntity.Action.ATTACK
                        && this.isNpcEntity(entity)) {
                    this.pendingChestPos = null;
                    this.openPending = true;
                }
            }
        }
        if (packet instanceof C0EPacketClickWindow) {
            C0EPacketClickWindow click = (C0EPacketClickWindow) packet;
            if (mc.currentScreen instanceof GuiInventory && click.getMode() < 1
                    && click.getClickedItem() != null) {
                inventoryClicking = true;
            }
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }
        switch (this.mode.getValue()) {
            case MODE_BUFFER_ABUSE:
                this.bufferAbuseMotion();
                break;
            case MODE_WATCHDOG:
                this.watchdogTick();
                break;
            default:
                break;
        }
    }

    private void bufferAbuseMotion() {
        if (!this.isBuffering()) {
            this.waitedTick = false;
            this.flushed = false;
            return;
        }
        if (this.flushed) {
            return;
        }
        if (!this.waitedTick) {
            this.waitedTick = true;
            return;
        }
        for (int i = 0; i < this.amount.getValue(); i++) {
            PacketUtil.sendPacketNoEvent(new C0EPacketClickWindow());
        }
        for (Packet<?> queued = this.queuedPackets.poll();
             queued != null; queued = this.queuedPackets.poll()) {
            PacketUtil.sendPacketNoEvent(queued);
        }
        this.flushed = true;
    }

    private boolean isBuffering() {
        return this.clickCount > 0 && this.clickCount % this.clicks.getValue() == 0;
    }

    @EventTarget
    public void onStrafe(StrafeEvent event) {
        if (!this.isEnabled() || this.mode.getValue() != MODE_BUFFER_ABUSE) {
            return;
        }
        if (this.isBuffering() && !this.flushed) {
            event.setForward(0.0F);
            event.setStrafe(0.0F);
        }
    }

    private void watchdogMotion() {
        if (!this.screenAllowed()) {
            return;
        }
        if (!(mc.currentScreen instanceof GuiInventory)) {
            inventoryClicking = false;
        }
        if (mc.currentScreen instanceof GuiInventory && !inventoryClicking
                && !this.sentWindowPacket) {
            PacketUtil.sendPacketNoEvent(
                    new C0DPacketCloseWindow(mc.thePlayer.inventoryContainer.windowId));
        }

        boolean containerBusy = mc.currentScreen instanceof GuiChest || inventoryClicking;
        Speed speed = (Speed) Myau.moduleManager.modules.get(Speed.class);
        if (containerBusy && !this.predictionMode.getValue()) {
            if (speed != null && speed.isEnabled() && !this.speedSuppressed) {
                mc.thePlayer.motionX *= -0.1;
                mc.thePlayer.motionZ *= -0.1;
                this.speedSuppressed = true;
            }
            boolean wholeY = !(Math.abs(mc.thePlayer.posY - Math.round(mc.thePlayer.posY)) > 0.03);

            if (this.groundTicks < 10 && wholeY && !(mc.currentScreen instanceof GuiChest)) {
                this.strafe(0.0365);
            } else if (!mc.thePlayer.onGround) {
                this.stop();
            } else if (mc.thePlayer.isPotionActive(Potion.moveSpeed)) {
                int amplifier = 1 + mc.thePlayer.getActivePotionEffect(Potion.moveSpeed).getAmplifier();
                this.strafe((amplifier > 1 ? 0.0185 : 0.0635) * amplifier);
            } else {
                this.strafe(0.09);
            }
            if (AccessorEntityLivingBase.isJumping(mc.thePlayer)) {
                this.stop();
            }
            this.preventDiagonalSpeed();
        } else if (this.speedSuppressed) {
            if (speed != null) {
                speed.setEnabled(true);
            }
            this.speedSuppressed = false;
        }

        if (containerBusy && this.predictionMode.getValue()) {
            mc.thePlayer.setSprinting(false);
            if (!mc.thePlayer.isPotionActive(Potion.moveSpeed)) {
                AccessorKeyBinding.setPressed(mc.gameSettings.keyBindSprint, false);
                AccessorKeyBinding.setPressed(mc.gameSettings.keyBindJump, false);
            }
        }
    }


    private boolean killAuraBusy() {
        KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        return killAura != null && killAura.isEnabled() && killAura.getTarget() != null;
    }

    private void resetWatchdogExtras() {
        this.flushBlinkQueue();
        this.shouldBlink = false;
        this.stopMovement = false;
        this.ticksSinceClick = 0;
    }

    private void flushBlinkQueue() {
        while (!this.blinkQueue.isEmpty()) {
            Packet<?> queued = this.blinkQueue.pollFirst();
            if (queued != null) {
                PacketUtil.sendPacketNoEvent(queued);
            }
        }
    }

    private void watchdog2Packet(PacketEvent event, Packet<?> packet) {
        if (mc.thePlayer == null || mc.theWorld == null || PlayerUtil.isUsingItem()
                || this.killAuraBusy()) {
            return;
        }
        if (packet instanceof C0EPacketClickWindow) {
            C0EPacketClickWindow click = (C0EPacketClickWindow) packet;
            int clickMode = click.getMode();
            boolean allowed = clickMode == CLICK_MODE_QUICK_MOVE || clickMode == CLICK_MODE_SWAP
                    || clickMode == CLICK_MODE_THROW;
            if (click.getWindowId() == mc.thePlayer.inventoryContainer.windowId && allowed) {
                PacketUtil.sendPacketNoEvent(new C0DPacketCloseWindow(click.getWindowId()));
            } else {
                this.shouldBlink = true;
            }
        } else if (packet instanceof C0DPacketCloseWindow) {
            this.shouldBlink = false;
        } else if (packet instanceof S01PacketJoinGame
                || packet instanceof S08PacketPlayerPosLook) {
            this.blinkQueue.clear();
            this.shouldBlink = false;
        } else if (this.shouldBlink && event.getType() == EventType.SEND
                && !(packet instanceof C00PacketKeepAlive)
                && !(packet instanceof C0FPacketConfirmTransaction)) {
            this.blinkQueue.add(packet);
            event.setCancelled(true);
            if (this.blinkQueue.size() >= BLINK_QUEUE_LIMIT) {
                this.shouldBlink = false;
                this.flushBlinkQueue();
            }
        }
        if (mc.currentScreen == null) {
            this.shouldBlink = false;
            this.flushBlinkQueue();
        }
    }

    private void watchdog2Tick() {
        if (mc.thePlayer == null || PlayerUtil.isUsingItem() || !this.screenAllowed()
                || this.killAuraBusy()) {
            return;
        }
        if (mc.currentScreen == null || !this.screenAllowsMovement()) {
            return;
        }
        this.updateMovementKeyStates();
    }

    private void watchdog3Tick() {
        if (mc.thePlayer == null || mc.currentScreen == null || !this.screenAllowsMovement()
                || !this.screenAllowed()) {
            this.stopMovement = false;
            this.ticksSinceClick = 0;
            return;
        }
        if (this.stopMovement) {
            this.ticksSinceClick++;
            if (this.ticksSinceClick >= WATCHDOG_3_STOP_TICKS) {
                this.stopMovement = false;
                this.ticksSinceClick = 0;
            }
        }
        if (this.stopMovement) {
            AccessorKeyBinding.setPressed(mc.gameSettings.keyBindForward, false);
            AccessorKeyBinding.setPressed(mc.gameSettings.keyBindBack, false);
            AccessorKeyBinding.setPressed(mc.gameSettings.keyBindLeft, false);
            AccessorKeyBinding.setPressed(mc.gameSettings.keyBindRight, false);
            AccessorKeyBinding.setPressed(mc.gameSettings.keyBindJump, false);
            AccessorKeyBinding.setPressed(mc.gameSettings.keyBindSprint, false);
            return;
        }
        this.updateMovementKeyStates();
        if (!this.allowJumping.getValue()) {
            AccessorKeyBinding.setPressed(mc.gameSettings.keyBindJump, false);
        }
        if (!this.allowSprinting.getValue()) {
            AccessorKeyBinding.setPressed(mc.gameSettings.keyBindSprint, false);
            mc.thePlayer.setSprinting(false);
        }
    }

    private void watchdogTick() {
        if (mc.theWorld == null || mc.thePlayer.ticksExisted < 50) {
            return;
        }
        this.beginOpenMeasurement();
        this.finishOpenMeasurement();
        this.updateChestOpenState();
    }

    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.thePlayer.movementInput == null) {
            return;
        }
        if (this.mode.getValue() == MODE_BUFFER_ABUSE) {
            if (this.isBuffering() && !this.flushed) {
                mc.thePlayer.movementInput.jump = false;
            }
            return;
        }
        if (this.mode.getValue() != MODE_WATCHDOG || !this.screenAllowed()) {
            return;
        }
        boolean inChest = mc.currentScreen instanceof GuiChest;
        if (chestOpenConfirmed && mc.thePlayer.ticksExisted % 5 != 0 && inChest) {
            this.zeroInput();
        }
        if (!inChest) {
            chestOpenConfirmed = false;
        }

        boolean containerBusy = inChest || inventoryClicking;
        if (containerBusy && this.predictionMode.getValue()
                && (mc.thePlayer.isPotionActive(Potion.moveSpeed) || !mc.thePlayer.onGround)) {
            this.zeroInput();
        } else if (inventoryClicking && !this.inputDelayPassed) {
            this.zeroInput();
            if (this.inputBlockStart == 0L) {
                this.inputBlockStart = System.currentTimeMillis();
            }
        } else {
            this.inputBlockStart = 0L;
        }
        if (this.inputBlockStart != 0L
                && System.currentTimeMillis() - this.inputBlockStart >= 60L) {
            this.inputDelayPassed = true;
            this.inputBlockStart = 0L;
        }
        if (!containerBusy) {
            this.inputDelayPassed = false;
        }
    }

    private void zeroInput() {
        mc.thePlayer.movementInput.moveForward = 0.0F;
        mc.thePlayer.movementInput.moveStrafe = 0.0F;
    }

    private int countHeldDirectionKeys() {
        int held = 0;
        KeyBinding[] keys = new KeyBinding[]{
                mc.gameSettings.keyBindForward, mc.gameSettings.keyBindRight,
                mc.gameSettings.keyBindBack, mc.gameSettings.keyBindLeft};
        for (KeyBinding key : keys) {
            if (key.isKeyDown()) {
                held++;
            }
        }
        return held;
    }

    private void beginOpenMeasurement() {
        if (!this.openPending) {
            return;
        }
        this.openSentTick = mc.thePlayer.ticksExisted;
        this.openPending = false;
        this.awaitingChestGui = true;
    }

    private void finishOpenMeasurement() {
        if (!this.awaitingChestGui) {
            return;
        }
        if (mc.currentScreen instanceof GuiChest) {
            this.openLatencyTicks = mc.thePlayer.ticksExisted - this.openSentTick;
            if (this.chestOpenTick == -1) {
                this.chestOpenTick = mc.thePlayer.ticksExisted;
            }
            this.awaitingChestGui = false;
            return;
        }
        if (mc.thePlayer.ticksExisted - this.openSentTick > 40) {
            this.awaitingChestGui = false;
        }
    }

    private void updateChestOpenState() {
        if (!(mc.currentScreen instanceof GuiChest)) {
            this.chestOpenTick = -1;
            chestOpenConfirmed = false;
            return;
        }
        if (this.chestOpenTick == -1) {
            this.chestOpenTick = mc.thePlayer.ticksExisted;
        }
        if (!chestOpenConfirmed && this.openLatencyTicks >= 0
                && mc.thePlayer.ticksExisted - this.chestOpenTick
                >= this.openLatencyTicks - this.ticks.getValue()) {
            chestOpenConfirmed = true;
        }
    }

    private void resetOpenMeasurement() {
        this.openSentTick = -1;
        this.openLatencyTicks = -1;
        this.chestOpenTick = -1;
        this.pendingChestPos = null;
        this.openPending = false;
        this.awaitingChestGui = false;
    }

    private boolean isChest(BlockPos position) {
        if (position == null || mc.theWorld == null || position.equals(new BlockPos(-1, -1, -1))) {
            return false;
        }
        Block block = mc.theWorld.getBlockState(position).getBlock();
        return block == Blocks.chest || block == Blocks.trapped_chest || block == Blocks.ender_chest;
    }

    private boolean isNpcEntity(Entity entity) {
        if (entity == null || entity == mc.thePlayer) {
            return false;
        }
        if (entity instanceof EntityPlayer) {
            return mc.getNetHandler() != null
                    && mc.getNetHandler().getPlayerInfo(((EntityPlayer) entity).getUniqueID()) == null;
        }
        if (Boolean.TRUE.equals(this.everMoved.get(entity))) {
            return false;
        }
        return this.isStationary(entity);
    }

    private boolean isStationary(Entity entity) {
        return Math.abs(entity.posX - entity.lastTickPosX) < 0.03
                && Math.abs(entity.posY - entity.lastTickPosY) < 0.03
                && Math.abs(entity.posZ - entity.lastTickPosZ) < 0.03
                && Math.abs(entity.motionX) < 0.03
                && Math.abs(entity.motionY) < 0.03
                && Math.abs(entity.motionZ) < 0.03;
    }

    private boolean isMoving() {
        return mc.thePlayer.movementInput != null
                && (mc.thePlayer.movementInput.moveForward != 0.0F
                || mc.thePlayer.movementInput.moveStrafe != 0.0F);
    }

    private void strafe(double speed) {
        if (this.isMoving()) {
            MoveUtil.setSpeed(speed, MoveUtil.getMoveYaw());
        }
    }

    private void stop() {
        mc.thePlayer.motionX = 0.0;
        mc.thePlayer.motionZ = 0.0;
    }

    private void preventDiagonalSpeed() {
        if (this.countHeldDirectionKeys() == 1 || !this.isMoving()) {
            return;
        }
        double correction = mc.thePlayer.onGround
                ? 0.0026000750109401644 : 5.199896488849598E-4;
        MoveUtil.addSpeed(-correction, MoveUtil.getMoveYaw());
    }
}
