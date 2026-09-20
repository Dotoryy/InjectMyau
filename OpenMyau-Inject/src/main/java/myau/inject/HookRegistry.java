package myau.inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HookRegistry {
    public enum Position {
        HEAD,
        RETURN,
        BEFORE_INVOKE,
        AFTER_INVOKE,
        REPLACE_INVOKE,
        BEFORE_FIELD,
        REPLACE_FIELD,
        MODIFY_RETURN,
        MODIFY_CONSTANT,
        MODIFY_STORE,
        MODIFY_ARGUMENT
    }
    public static final class Hook {
        public final String owner;
        public final String method;
        public final String descriptor;
        public final Position position;
        public final String callbackOwner;
        public final String callbackName;
        public final String callbackDescriptor;
        public final String argSpec;
        public final boolean cancellable;
        public final boolean required;
        public final String nameOwner;
        public final String targetOwner;
        public final String targetMember;
        public final String targetDescriptor;
        public final boolean targetRemap;
        public final String targetNameOwner;

        public final int ordinal;
        Hook(String owner, String method, String descriptor, Position position,
             String callbackOwner, String callbackName, String callbackDescriptor,
             String argSpec, boolean cancellable, boolean required, String nameOwner,
             String targetOwner, String targetMember, String targetDescriptor,
             boolean targetRemap, String targetNameOwner, int ordinal) {
            this.owner = owner;
            this.method = method;
            this.descriptor = descriptor;
            this.position = position;
            this.callbackOwner = callbackOwner;
            this.callbackName = callbackName;
            this.callbackDescriptor = callbackDescriptor;
            this.argSpec = argSpec == null ? "" : argSpec;
            this.cancellable = cancellable;
            this.required = required;
            this.nameOwner = nameOwner == null ? owner : nameOwner;
            this.targetOwner = targetOwner;
            this.targetMember = targetMember;
            this.targetDescriptor = targetDescriptor;
            this.targetRemap = targetRemap;
            this.targetNameOwner = targetNameOwner == null ? targetOwner : targetNameOwner;
            this.ordinal = ordinal;
        }
        @Override
        public String toString() {
            return owner + "." + method + " @" + position + " -> "
                    + callbackOwner + "." + callbackName;
        }
    }
    public static final class Builder {
        private final String owner;
        private final String method;
        private final String descriptor;
        private Position position = Position.HEAD;
        private String callbackOwner = Callbacks.OWNER;
        private String callbackName;
        private String callbackDescriptor = "()V";
        private String argSpec = "";
        private boolean cancellable;
        private boolean required = true;
        private String nameOwner;
        private String targetOwner;
        private String targetMember;
        private String targetDescriptor;
        private boolean targetRemap = true;
        private String targetNameOwner;
        private int ordinal = -1;
        Builder(String owner, String method, String descriptor) {
            this.owner = owner;
            this.method = method;
            this.descriptor = descriptor;
        }
        public Builder in(String internalName) {
            this.callbackOwner = internalName;
            return this;
        }
        public Builder at(Position value) {
            this.position = value;
            return this;
        }

        public Builder calls(String name, String descriptor) {
            this.callbackName = name;
            this.callbackDescriptor = descriptor;
            return this;
        }
        public Builder calls(String name) {
            return calls(name, "()V");
        }
        public Builder args(String spec) {
            this.argSpec = spec;
            return this;
        }
        public Builder cancellable() {
            this.cancellable = true;
            return this;
        }
        public Builder optional() {
            this.required = false;
            return this;
        }
        public Builder namesFrom(String mcpClass) {
            this.nameOwner = mcpClass;
            return this;
        }

        public Builder invoking(String owner, String member, String descriptor) {
            return member(owner, member, descriptor, true);
        }
        public Builder invokingUnmapped(String owner, String member, String descriptor) {
            return member(owner, member, descriptor, false);
        }
        public Builder field(String owner, String member, String descriptor) {
            return member(owner, member, descriptor, true);
        }
        private Builder member(String owner, String member, String descriptor, boolean remap) {
            this.targetOwner = owner;
            this.targetMember = member;
            this.targetDescriptor = descriptor;
            this.targetRemap = remap;
            return this;
        }
        public Builder storing(String descriptor) {
            this.targetDescriptor = descriptor;
            return this;
        }
        public Builder membersOf(String mcpClass) {
            this.targetNameOwner = mcpClass;
            return this;
        }
        public Builder replacing(String value) {
            this.targetMember = value;
            return this;
        }
        public Builder ordinal(int value) {
            this.ordinal = value;
            return this;
        }
        public void add() {
            HookRegistry.add(new Hook(owner, method, descriptor, position,
                    callbackOwner, callbackName, callbackDescriptor, argSpec,
                    cancellable, required, nameOwner, targetOwner, targetMember,
                    targetDescriptor, targetRemap, targetNameOwner, ordinal));
        }
    }
    private static final Map<String, List<Hook>> BY_OWNER = new LinkedHashMap<String, List<Hook>>();

    private HookRegistry() {
    }
    public static Builder hook(String owner, String method, String descriptor) {
        return new Builder(owner, method, descriptor);
    }
    public static Builder hook(String owner, String method) {
        return new Builder(owner, method, null);
    }
    static void add(Hook hook) {
        List<Hook> list = BY_OWNER.get(hook.owner);
        if (list == null) {
            list = new ArrayList<Hook>();
            BY_OWNER.put(hook.owner, list);
        }
        list.add(hook);
    }

    public static Iterable<String> owners() {
        return BY_OWNER.keySet();
    }

    public static List<Hook> forOwner(String mcpOwner) {
        List<Hook> list = BY_OWNER.get(mcpOwner);
        return list == null ? new ArrayList<Hook>() : list;
    }

    public static String keyOf(Hook hook) {
        return hook.owner + "." + hook.method + " @" + hook.position
                + " -> " + hook.callbackName;
    }

    public static List<Hook> allHooks() {
        List<Hook> all = new ArrayList<Hook>();
        for (List<Hook> list : BY_OWNER.values()) {
            all.addAll(list);
        }
        return all;
    }

    public static int size() {
        int total = 0;
        for (List<Hook> list : BY_OWNER.values()) {
            total += list.size();
        }
        return total;
    }
}
