package myau.events;

import myau.event.events.Event;
import net.minecraft.entity.Entity;

public class AttackEvent implements Event {
    private final Entity target;
    private final boolean fromController;
    private boolean cancelled;

    public AttackEvent(Entity target) {
        this(target, false);
    }

    public AttackEvent(Entity target, boolean fromController) {
        this.target = target;
        this.fromController = fromController;
        this.cancelled = false;
    }

    public boolean isFromController() {
        return this.fromController;
    }

    public Entity getTarget() {
        return this.target;
    }

    public boolean isCancelled() {
        return this.cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
