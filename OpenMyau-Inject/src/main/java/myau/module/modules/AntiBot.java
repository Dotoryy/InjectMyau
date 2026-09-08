package myau.module.modules;

import myau.Myau;
import myau.bot.BotCheck;
import myau.bot.checks.MiddleClickCheck;
import myau.bot.checks.MojangProfileCheck;
import myau.bot.checks.SimpleChecks;
import myau.bot.checks.TabSnapshotCheck;
import myau.event.EventTarget;
import myau.inject.Log;
import myau.event.types.EventType;
import myau.events.LoadWorldEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AntiBot extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final BooleanProperty tab = new BooleanProperty("tab", true);
    public final BooleanProperty hypixel = new BooleanProperty("hypixel", true);
    public final BooleanProperty noPing = new BooleanProperty("no-ping", false);
    public final BooleanProperty npcUuid = new BooleanProperty("npc-uuid", false);
    public final BooleanProperty duplicateName = new BooleanProperty("duplicate-name", false);
    public final BooleanProperty duplicateUuid = new BooleanProperty("duplicate-uuid", false);
    public final BooleanProperty colour = new BooleanProperty("colour", false);
    public final BooleanProperty funcraft = new BooleanProperty("funcraft", false);
    public final BooleanProperty cubecraftBedrock = new BooleanProperty("cubecraft-bedrock", false);
    public final BooleanProperty timeVisible = new BooleanProperty("time-visible", false);
    public final BooleanProperty middleClick = new BooleanProperty("middle-click", false);
    public final BooleanProperty tabSnapshot = new BooleanProperty("tab-snapshot", false);
    public final BooleanProperty mojangProfile = new BooleanProperty("mojang-profile", false);
    public final BooleanProperty debug = new BooleanProperty("debug", false);
    private final Map<Integer, String> debugReported = new HashMap<Integer, String>();

    private static final class Slot {
        final BooleanProperty setting;
        final BotCheck check;
        boolean wasOn;
        Slot(BooleanProperty setting, BotCheck check) {
            this.setting = setting;
            this.check = check;
        }
    }
    private final List<Slot> slots = new ArrayList<Slot>();
    public AntiBot() {
        super("Anti Bot", true);
        this.add(this.tab, new SimpleChecks.TabCheck());
        this.add(this.hypixel, new SimpleChecks.HypixelCheck());
        this.add(this.noPing, new SimpleChecks.NoPingCheck());
        this.add(this.npcUuid, new SimpleChecks.NpcUuidCheck());
        this.add(this.duplicateName, new SimpleChecks.DuplicateNameCheck());
        this.add(this.duplicateUuid, new SimpleChecks.DuplicateUuidCheck());
        this.add(this.colour, new SimpleChecks.ColourCheck());
        this.add(this.funcraft, new SimpleChecks.FuncraftCheck());
        this.add(this.cubecraftBedrock, new SimpleChecks.CubecraftBedrockCheck());
        this.add(this.timeVisible, new SimpleChecks.TimeVisibleCheck());
        this.add(this.middleClick, new MiddleClickCheck());
        this.add(this.tabSnapshot, new TabSnapshotCheck());
        this.add(this.mojangProfile, new MojangProfileCheck());
    }

    private void add(BooleanProperty setting, BotCheck check) {
        this.slots.add(new Slot(setting, check));
    }
    public static boolean isBot(Entity entity) {
        AntiBot module = (AntiBot) Myau.moduleManager.modules.get(AntiBot.class);
        if (module == null || !module.isEnabled()) {
            return false;
        }
        return Myau.botManager.isBot(entity);
    }
    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE || !this.isEnabled()) {
            return;
        }
        if (mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        for (Slot slot : this.slots) {
            boolean on = slot.setting.getValue();
            if (on) {
                try {
                    slot.check.update();
                } catch (Throwable failed) {
                    Log.swallowed(failed);
                }
            } else if (slot.wasOn) {
                slot.check.onDisabled();
            }
            slot.wasOn = on;
        }
        if (this.debug.getValue()) {
            this.reportFlags();
        } else if (!this.debugReported.isEmpty()) {
            this.debugReported.clear();
        }
    }

    private void reportFlags() {
        for (Object raw : mc.theWorld.playerEntities) {
            if (!(raw instanceof EntityPlayer) || raw == mc.thePlayer) {
                continue;
            }
            EntityPlayer player = (EntityPlayer) raw;
            StringBuilder flags = new StringBuilder();
            for (Slot slot : this.slots) {
                if (!slot.setting.getValue()) {
                    continue;
                }
                if (Myau.botManager.isBot(slot.check, player)) {
                    if (flags.length() > 0) {
                        flags.append(',');
                    }
                    flags.append(slot.check.getName());
                }
            }
            String verdict = flags.length() == 0 ? "none" : flags.toString();
            Integer id = Integer.valueOf(player.getEntityId());
            if (!verdict.equals(this.debugReported.get(id))) {
                this.debugReported.put(id, verdict);
                Log.line(String.format("[AB] %s id=%d uuid=%s v=%d tab=%b -> %s",
                        player.getName(), player.getEntityId(),
                        player.getUniqueID(), player.getUniqueID().version(),
                        mc.getNetHandler() != null
                                && mc.getNetHandler().getPlayerInfo(player.getUniqueID()) != null,
                        verdict));
            }
        }
    }

    @EventTarget
    public void onWorldLoad(LoadWorldEvent event) {
        this.forgetEverything();
    }
    @Override
    public void onDisabled() {
        this.forgetEverything();
    }
    private void forgetEverything() {
        for (Slot slot : this.slots) {
            try {
                slot.check.onDisabled();
            } catch (Throwable ignored) {
            }
            slot.wasOn = false;
        }
        Myau.botManager.clear();
    }
}
