package myau.events;

import myau.event.events.callables.EventCancellable;
import net.minecraft.util.MovingObjectPosition;

public class PreAttackEvent extends EventCancellable {
    public final MovingObjectPosition objectMouseOver;

    public PreAttackEvent(MovingObjectPosition objectMouseOver) {
        this.objectMouseOver = objectMouseOver;
    }
}
