package myau.ui.clickgui;

import myau.Myau;
import myau.module.Module;
import myau.module.modules.*;
import myau.module.modules.Timer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class Categories {
    private static final Map<Category, List<Module>> BY_CATEGORY = new EnumMap<Category, List<Module>>(Category.class);
    private Categories() {
    }
    public static List<Module> modulesOf(Category category) {
        List<Module> modules = BY_CATEGORY.get(category);
        if (modules == null) {
            modules = build(category);
            BY_CATEGORY.put(category, modules);
        }
        return modules;
    }

    public static void verifyComplete() {
        List<Module> listed = new ArrayList<Module>();
        for (Category category : Category.values()) {
            listed.addAll(modulesOf(category));
        }
        for (Module module : Myau.moduleManager.modules.values()) {
            if (!listed.contains(module)) {
                throw new RuntimeException(module.getClass().getName() + " is unregistered to click gui.");
            }
        }
    }
    private static List<Module> build(Category category) {
        List<Module> modules = new ArrayList<Module>();
        switch (category) {
            case THEME:
                add(modules, myau.module.modules.Theme.class);
                break;
            case COMBAT:
                add(modules, AimAssist.class);
                add(modules, AutoClicker.class);
                add(modules, Autoblock.class);
                add(modules, Displace.class);
                add(modules, KillAura.class);
                add(modules, TeleportAura.class);
                add(modules, Wtap.class);
                add(modules, Velocity.class);
                add(modules, Freeze.class);
                add(modules, Reach.class);
                add(modules, TargetStrafe.class);
                add(modules, NoHitDelay.class);
                add(modules, AntiFireball.class);
                add(modules, LagRange.class);
                add(modules, KnockbackDelay.class);
                add(modules, Backtrack.class);
                add(modules, HitBox.class);
                add(modules, MoreKB.class);
                add(modules, Refill.class);
                add(modules, HitSelect.class);
                add(modules, AntiBot.class);
                break;
            case MOVEMENT:
                add(modules, AntiAFK.class);
                add(modules, Fly.class);
                add(modules, Speed.class);
                add(modules, LongJump.class);
                add(modules, Sprint.class);
                add(modules, SafeWalk.class);
                add(modules, Jesus.class);
                add(modules, Blink.class);
                add(modules, FakeLag.class);
                add(modules, NoFall.class);
                add(modules, NoSlow.class);
                add(modules, KeepSprint.class);
                add(modules, BridgeAssist.class);
                add(modules, NoJumpDelay.class);
                add(modules, AntiVoid.class);
                add(modules, Stasis.class);
                add(modules, Timer.class);
                add(modules, InventoryMove.class);
                add(modules, Jump45.class);
                add(modules, SnapTap.class);
                break;
            case RENDER:
                add(modules, BlockCounter.class);
                add(modules, FallView.class);
                add(modules, ESP.class);
                add(modules, Chams.class);
                add(modules, FullBright.class);
                add(modules, CuteVisuals.class);
                add(modules, Tracers.class);
                add(modules, NameTags.class);
                add(modules, Xray.class);
                add(modules, TargetHUD.class);
                add(modules, Indicators.class);
                add(modules, BedESP.class);
                add(modules, ItemESP.class);
                add(modules, ViewClip.class);
                add(modules, NoHurtCam.class);
                add(modules, HUD.class);
                add(modules, GuiModule.class);
                add(modules, ChestESP.class);
                add(modules, Trajectories.class);
                add(modules, Radar.class);
                break;
            case PLAYER:
                add(modules, Clutch.class);
                add(modules, AutoHeal.class);
                add(modules, AutoTool.class);
                add(modules, ChestStealer.class);
                add(modules, InvManager.class);
                add(modules, Scaffold.class);
                add(modules, GodBridge.class);
                add(modules, BlockIn.class);
                add(modules, FastBreak.class);
                add(modules, AutoPot.class);
                add(modules, FastPlace.class);
                add(modules, LadderClutch.class);
                add(modules, GhostHand.class);
                add(modules, MCF.class);
                add(modules, AntiDebuff.class);
                add(modules, Freecam.class);
                break;
            case MISC:
                add(modules, UnInject.class);
                add(modules, Spammer.class);
                add(modules, BedNuker.class);
                add(modules, BedDefender.class);
                add(modules, BedTracker.class);
                add(modules, LightningTracker.class);
                add(modules, Accounts.class);
                add(modules, NoRotate.class);
                add(modules, Disabler.class);
                add(modules, NickHider.class);
                add(modules, AntiObbyTrap.class);
                add(modules, AntiObfuscate.class);
                add(modules, AutoAnduril.class);
                add(modules, InventoryClicker.class);
                break;
            default:
                break;
        }
        modules.sort(Comparator.comparing(module -> module.getName().toLowerCase()));
        return modules;
    }
    private static void add(List<Module> modules, Class<? extends Module> type) {
        Module module = Myau.moduleManager.getModule(type);
        if (module != null) {
            modules.add(module);
        }
    }
}
