package myau.events;

import myau.event.events.callables.EventCancellable;

public class MouseButtonEvent extends EventCancellable {
    private final int button;
    private final boolean pressed;

    public MouseButtonEvent(int button, boolean pressed) {
        this.button = button;
        this.pressed = pressed;
    }

    public int getButton() {
        return this.button;
    }

    public boolean isPressed() {
        return this.pressed;
    }
}
