package myau.events;

import myau.event.events.callables.EventCancellable;
import net.minecraft.item.ItemStack;

public class UseItemEvent extends EventCancellable {
    public final ItemStack usedItemStack;

    public UseItemEvent(ItemStack usedItemStack) {
        this.usedItemStack = usedItemStack;
    }
}
