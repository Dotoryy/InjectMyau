package myau.property.properties;

import com.google.gson.JsonObject;
import myau.property.Property;

import java.util.function.BooleanSupplier;

public class ModeProperty extends Property<Integer> {
    private final String[] modes;
    private final java.util.Map<String, Integer> aliases = new java.util.HashMap<>();

    public ModeProperty(String name, Integer value, String[] modes) {
        this(name, value, modes, null);
    }

    public ModeProperty(String name, Integer value, String[] modes, BooleanSupplier check) {
        super(name, value, check);
        this.modes = modes;
    }

    @Override
    public String getValuePrompt() {
        return String.join(", ", this.modes);
    }

    public ModeProperty withAlias(String legacy, int index) {
        this.aliases.put(normalize(legacy), index);
        return this;
    }

    private static String normalize(String value) {
        return value.replace("_", "").toLowerCase(java.util.Locale.ROOT);
    }

    public String[] getModes() {
        return this.modes;
    }
    public int getModeCount() {
        return this.modes.length;
    }
    public String getModeString() {
        int index = this.getValue();
        return index >= 0 && index < this.modes.length ? this.modes[index] : "";
    }
    @Override
    public String formatValue() {
        String index = this.getModeString();
        return index.isEmpty() ? "&4?" : String.format("&9%s", index);
    }

    @Override
    public boolean parseString(String string) {
        String valueStr = string.replace("_", "");
        for (int i = 0; i < this.modes.length; i++) {
            if (valueStr.equalsIgnoreCase(this.modes[i].replace("_", ""))) {
                return this.setValue(i);
            }
        }
        Integer alias = this.aliases.get(normalize(string));
        return alias != null && this.setValue(alias);
    }
    @Override
    public boolean read(JsonObject jsonObject) {
        return this.parseString(jsonObject.get(this.getName()).getAsString());
    }
    @Override
    public void write(JsonObject jsonObject) {
        jsonObject.addProperty(this.getName(), this.getModeString());
    }
    public void nextMode() {
        int current = this.getValue();
        int next = current + 1;
        if (next >= this.modes.length) {
            next = 0;
        }
        this.setValue(next);
    }
    public void previousMode() {
        int current = this.getValue();
        int prev = current - 1;
        if (prev < 0) {
            prev = this.modes.length - 1;
        }
        this.setValue(prev);
    }
}
