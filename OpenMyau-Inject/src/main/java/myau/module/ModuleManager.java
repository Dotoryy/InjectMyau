package myau.module;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.KeyEvent;
import myau.events.TickEvent;
import myau.module.modules.GuiModule;
import myau.module.modules.HUD;
import myau.module.modules.Timer;
import myau.util.ChatUtil;
import myau.util.SoundUtil;

import java.util.LinkedHashMap;

public class ModuleManager {
    private boolean sound = false;
    public final LinkedHashMap<Class<?>, Module> modules = new LinkedHashMap<>();

    public Module getModule(String string) {
        String wanted = squash(string);
        return this.modules
                .values()
                .stream()
                .filter(module -> squash(module.getName()).equalsIgnoreCase(wanted)
                        || java.util.Arrays.stream(module.getLegacyNames())
                        .anyMatch(legacy -> squash(legacy).equalsIgnoreCase(wanted)))
                .findFirst()
                .orElse(null);
    }
    public static String squash(String name) {
        return name == null ? "" : name.replace(" ", "");
    }
    public Module getModule(Class<?> clazz){
        return this.modules.get(clazz);
    }
    public void playSound() {
        this.sound = true;
    }
    @EventTarget
    public void onKey(KeyEvent event) {
        for (Module module : this.modules.values()) {
            if (module.getKey() != event.getKey()) {
                continue;
            }
            if (event.isGuiOpen() && !module.worksWithGuiOpen()) {
                continue;
            }
            if (module instanceof Timer && ((Timer) module).consumeQueuedToggle()) {
                continue;
            }
            boolean shouldNotify = module.toggle();
            HUD hud = (HUD) this.modules.get(HUD.class);
            if (hud != null && shouldNotify) {
                shouldNotify = hud.toggleAlerts.getValue();
            }
            if(module instanceof GuiModule){
                shouldNotify = false;
            }
            if (shouldNotify) {
                String status = module.isEnabled() ? "&a&lON" : "&c&lOFF";
                String message = String.format("%s%s: %s&r", Myau.clientName, module.getName(), status);
                ChatUtil.sendFormatted(message);
            }
        }
    }
    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() == EventType.PRE) {
            if (this.sound) {
                this.sound = false;
                SoundUtil.playSound("random.click");
            }
        }
    }
}
