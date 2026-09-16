package myau.util;

import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public class KeyBindUtil {
    public static String getKeyName(int keyCode) {
        if (keyCode < 0) {
            int mouseButton = keyCode + 100;
            switch (mouseButton) {
                case 0:
                    return "LMB";
                case 1:
                    return "RMB";
                case 2:
                    return "MMB";
                case 3:
                    return "MOUSE3";
                case 4:
                    return "MOUSE4";
                case 5:
                    return "MOUSE5";
                case 6:
                    return "MOUSE6";
                case 7:
                    return "MOUSE7";
                default:
                    String buttonName = Mouse.getButtonName(mouseButton);
                    return buttonName != null ? buttonName : "MOUSE" + mouseButton;
            }
        }
        if (keyCode >= Keyboard.KEYBOARD_SIZE) {
            return "KEY" + keyCode;
        }
        String name = Keyboard.getKeyName(keyCode);
        return name != null ? name : "KEY" + keyCode;
    }

    public static boolean isKeyDown(int keyCode) {
        if (keyCode < 0) {
            return Mouse.isButtonDown(keyCode + 100);
        }
        return keyCode > 0 && keyCode < Keyboard.KEYBOARD_SIZE && Keyboard.isKeyDown(keyCode);
    }

    public static void updateKeyState(int keyCode) {
        KeyBindUtil.setKeyBindState(keyCode, KeyBindUtil.isKeyDown(keyCode));
    }

    private static final ThreadLocal<Boolean> SYNTHETIC = new ThreadLocal<Boolean>();
    public static boolean isSynthetic() {
        return Boolean.TRUE.equals(SYNTHETIC.get());
    }

    public static void setKeyBindState(int keyCode, boolean pressed) {
        SYNTHETIC.set(Boolean.TRUE);
        try {
            KeyBinding.setKeyBindState(keyCode, pressed);
        } finally {
            SYNTHETIC.remove();
        }
    }
    public static void pressKeyOnce(int keyCode) {
        KeyBinding.onTick(keyCode);
    }
}
