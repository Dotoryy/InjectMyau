package myau.inject;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class HookTransformer implements ClassFileTransformer {
    private final Map<String, String> internalToMcp = new HashMap<String, String>();
    private final Set<String> applied = new HashSet<String>();
    private final java.util.concurrent.ConcurrentLinkedQueue<String> log =
            new java.util.concurrent.ConcurrentLinkedQueue<String>();
    private final List<Class<?>> targets = new ArrayList<Class<?>>();
    public HookTransformer(Class<?>[] loaded) {
        Map<String, String> nameToMcp = new HashMap<String, String>();
        for (String mcp : HookRegistry.owners()) {
            nameToMcp.put(mcp, mcp);
            internalToMcp.put(mcp.replace('.', '/'), mcp);
            String notch = MappingBridge.notchClass(mcp);
            if (notch != null) {
                nameToMcp.put(notch, mcp);
                internalToMcp.put(notch.replace('.', '/'), mcp);
            }
        }
        Set<String> found = new HashSet<String>();
        for (Class<?> candidate : loaded) {
            String mcp = nameToMcp.get(candidate.getName());
            if (mcp == null) {
                continue;
            }
            targets.add(candidate);
            found.add(mcp);
            ClassLoader cl = candidate.getClassLoader();
            log.offer("target " + mcp + " as " + candidate.getName()
                    + " [" + (cl == null ? "bootstrap" : cl.getClass().getSimpleName()) + "]");
        }

        for (String mcp : HookRegistry.owners()) {
            if (found.contains(mcp)) {
                continue;
            }
            log.offer("deferred " + mcp + " -- not loaded yet, will patch as it loads");
        }
    }
    public void warmUp(byte[] sample) {
        try {
            ClassNode node = new ClassNode();
            new ClassReader(sample).accept(node, 0);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            writer.toByteArray();
            InsnList list = new InsnList();
            LabelNode label = new LabelNode();
            list.add(new VarInsnNode(Opcodes.ALOAD, 0));
            list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Object",
                    "toString", "()Ljava/lang/String;", false));
            list.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/Boolean", "TRUE",
                    "Ljava/lang/Boolean;"));
            list.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Object"));
            list.add(new LdcInsnNode(Double.valueOf(0.0)));
            list.add(new JumpInsnNode(Opcodes.IFNULL, label));
            list.add(new InsnNode(Opcodes.POP));
            list.add(label);
            list.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            list.add(new IntInsnNode(Opcodes.BIPUSH, 1));
            list.add(new IincInsnNode(0, 1));
            list.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
            list.add(new MultiANewArrayInsnNode("[[I", 2));
            list.add(new TableSwitchInsnNode(0, 0, label, new LabelNode[]{label}));
            list.add(new LookupSwitchInsnNode(label, new int[]{0}, new LabelNode[]{label}));
            new TryCatchBlockNode(label, label, label, null);
            new LocalVariableNode("x", "I", null, label, label, 0);
            new LineNumberNode(1, label);
            Type.getArgumentTypes("(IZ)V");
        } catch (Throwable t) {
            log.offer("warm-up of the transformer failed: " + t);
        }
    }
    public List<Class<?>> targets() {
        return targets;
    }
    public List<String> drain() {
        List<String> taken = new ArrayList<String>();
        for (String line = log.poll(); line != null; line = log.poll()) {
            taken.add(line);
        }
        return taken;
    }
    public boolean hasLog() {
        return !log.isEmpty();
    }
    @Override
    public byte[] transform(ClassLoader loader, String internalName, Class<?> beingRedefined,
                            ProtectionDomain domain, byte[] original) {
        if (internalName == null) {
            return null;
        }
        String mcp = internalToMcp.get(internalName);
        if (mcp == null) {
            return null;
        }
        List<HookRegistry.Hook> hooks = HookRegistry.forOwner(mcp);
        if (hooks.isEmpty()) {
            return null;
        }
        try {
            ClassNode node = new ClassNode();
            new ClassReader(original).accept(node, 0);

            int installed = 0;
            for (HookRegistry.Hook hook : hooks) {
                MethodNode target = findMethod(node, hook.nameOwner, hook);
                if (target == null) {
                    log.offer("no method " + mcp + "." + hook.method);
                    continue;
                }
                if (!cancellableHere(hook)) {
                    log.offer("refusing cancellable " + mcp + "." + hook.method + " @"
                            + hook.position + " -- a cancel needs an empty stack, and there is"
                            + " none there");
                    continue;
                }
                int placed = insert(target, hook);
                if (placed == 0) {
                    MethodNode delegate = delegateOf(node, target);
                    if (delegate != null) {
                        placed = insert(delegate, hook);
                        if (placed > 0) {
                            log.offer("  " + mcp + "." + hook.method
                                    + " forwards to " + delegate.name + delegate.desc
                                    + " -- hooked there instead");
                        }
                    }
                }
                if (placed == 0) {
                    log.offer("no insertion point for " + mcp + "." + hook.method
                            + " @" + hook.position);
                    continue;
                }
                installed++;
                applied.add(mcp + "." + hook.method);
            }
            if (installed == 0) {
                return null;
            }

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            log.offer("patched " + mcp + " (" + installed + "/" + hooks.size() + ")");
            return writer.toByteArray();
        } catch (Throwable t) {
            log.offer("FAILED " + mcp + ": " + t);
            return null;
        }
    }
    private MethodNode findMethod(ClassNode node, String mcpOwner, HookRegistry.Hook hook) {
        String[] names = MappingBridge.methodNames(mcpOwner, hook.method);
        Set<String> descriptors = MappingBridge.descriptors(hook.descriptor);
        MethodNode byName = null;
        int matches = 0;
        for (MethodNode m : node.methods) {
            boolean nameMatches = false;
            for (String candidate : names) {
                if (candidate != null && candidate.equals(m.name)) {
                    nameMatches = true;
                    break;
                }
            }
            if (!nameMatches) {
                continue;
            }
            if (hook.descriptor != null) {
                if (descriptors.contains(m.desc)) {
                    return m;
                }
                continue;
            }
            matches++;
            byName = m;
        }
        return matches == 1 ? byName : null;
    }

    private int insert(MethodNode target, HookRegistry.Hook hook) {
        boolean readsArguments = hook.position == HookRegistry.Position.HEAD
                || hook.position == HookRegistry.Position.MODIFY_ARGUMENT;
        if (!readsArguments && hook.argSpec.length() > 0) {
            log.offer("REFUSING " + hook.owner + "." + hook.method + " @" + hook.position
                    + ": only a head hook may read arguments -- see callback()");
            return 0;
        }
        switch (hook.position) {
            case HEAD:
                target.instructions.insert(callback(target, hook));
                return 1;
            case RETURN: {
                List<AbstractInsnNode> returns = returns(target);
                for (AbstractInsnNode insn : returns) {
                    target.instructions.insertBefore(insn, callback(target, hook));
                }
                return returns.size();
            }
            case MODIFY_RETURN: {
                List<AbstractInsnNode> returns = returns(target);
                for (AbstractInsnNode insn : returns) {
                    target.instructions.insertBefore(insn, invokeCallback(hook));
                }
                return returns.size();
            }
            case BEFORE_INVOKE: {
                List<MethodInsnNode> calls = findCalls(target, hook);
                for (MethodInsnNode call : calls) {
                    target.instructions.insertBefore(call, callback(target, hook));
                }
                return calls.size();
            }
            case AFTER_INVOKE: {
                List<MethodInsnNode> calls = findCalls(target, hook);
                for (MethodInsnNode call : calls) {
                    target.instructions.insert(call, callback(target, hook));
                }
                return calls.size();
            }
            case REPLACE_INVOKE: {
                List<MethodInsnNode> calls = findCalls(target, hook);
                for (MethodInsnNode call : calls) {

                    target.instructions.set(call, invokeCallback(hook).getFirst());
                }
                return calls.size();
            }
            case BEFORE_FIELD: {
                List<FieldInsnNode> accesses = findFieldAccesses(target, hook);
                for (FieldInsnNode access : accesses) {
                    target.instructions.insertBefore(access, callback(target, hook));
                }
                return accesses.size();
            }
            case REPLACE_FIELD: {
                List<FieldInsnNode> accesses = findFieldAccesses(target, hook);
                for (FieldInsnNode access : accesses) {
                    target.instructions.set(access, invokeCallback(hook).getFirst());
                }
                return accesses.size();
            }
            case MODIFY_CONSTANT: {
                List<AbstractInsnNode> constants = findConstants(target, hook);
                for (AbstractInsnNode constant : constants) {
                    target.instructions.insert(constant, invokeCallback(hook));
                }
                return constants.size();
            }
            case MODIFY_STORE: {
                List<AbstractInsnNode> stores = findStores(target, hook);
                for (AbstractInsnNode store : stores) {
                    target.instructions.insertBefore(store, invokeCallback(hook));
                }
                return stores.size();
            }
            case MODIFY_ARGUMENT: {
                target.instructions.insert(modifyArgument(target, hook));
                return 1;
            }
            default:
                return 0;
        }
    }
    private List<AbstractInsnNode> returns(MethodNode target) {
        List<AbstractInsnNode> found = new ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode insn = target.instructions.getFirst();
             insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();
            if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) {
                found.add(insn);
            }
        }
        return found;
    }
    private InsnList invokeCallback(HookRegistry.Hook hook) {
        InsnList list = new InsnList();
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hook.callbackOwner,
                hook.callbackName, callbackDescriptor(hook), false));
        return list;
    }
    private InsnList modifyArgument(MethodNode target, HookRegistry.Hook hook) {
        boolean isStatic = (target.access & Opcodes.ACC_STATIC) != 0;
        Type[] args = Type.getArgumentTypes(target.desc);
        int index = Integer.parseInt(hook.argSpec.trim());
        int slot = isStatic ? 0 : 1;
        for (int i = 0; i < index; i++) {
            slot += args[i].getSize();
        }
        InsnList list = new InsnList();
        list.add(new VarInsnNode(args[index].getOpcode(Opcodes.ILOAD), slot));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hook.callbackOwner,
                hook.callbackName, callbackDescriptor(hook), false));
        list.add(new VarInsnNode(args[index].getOpcode(Opcodes.ISTORE), slot));
        return list;
    }
    private List<AbstractInsnNode> findConstants(MethodNode target, HookRegistry.Hook hook) {
        List<AbstractInsnNode> found = new ArrayList<AbstractInsnNode>();
        int seen = 0;
        for (AbstractInsnNode insn = target.instructions.getFirst();
             insn != null; insn = insn.getNext()) {
            Object value = constantValue(insn);
            if (value == null || !String.valueOf(value).equals(hook.targetMember)) {
                continue;
            }
            if (hook.ordinal < 0 || seen == hook.ordinal) {
                found.add(insn);
            }
            seen++;
        }
        if (found.isEmpty()) {
            log.offer("  wanted constant " + hook.targetMember + " ordinal " + hook.ordinal
                    + " in " + hook.method + ", saw " + seen + " candidates");
        }
        return found;
    }
    private Object constantValue(AbstractInsnNode insn) {
        if (insn instanceof LdcInsnNode) {
            Object value = ((LdcInsnNode) insn).cst;
            return value instanceof String ? null : value;
        }
        switch (insn.getOpcode()) {
            case Opcodes.ICONST_M1: return Integer.valueOf(-1);
            case Opcodes.ICONST_0: return Integer.valueOf(0);
            case Opcodes.ICONST_1: return Integer.valueOf(1);
            case Opcodes.ICONST_2: return Integer.valueOf(2);
            case Opcodes.ICONST_3: return Integer.valueOf(3);
            case Opcodes.ICONST_4: return Integer.valueOf(4);
            case Opcodes.ICONST_5: return Integer.valueOf(5);
            case Opcodes.LCONST_0: return Long.valueOf(0L);
            case Opcodes.LCONST_1: return Long.valueOf(1L);
            case Opcodes.FCONST_0: return Float.valueOf(0.0F);
            case Opcodes.FCONST_1: return Float.valueOf(1.0F);
            case Opcodes.FCONST_2: return Float.valueOf(2.0F);
            case Opcodes.DCONST_0: return Double.valueOf(0.0D);
            case Opcodes.DCONST_1: return Double.valueOf(1.0D);
            default: return null;
        }
    }
    private List<AbstractInsnNode> findStores(MethodNode target, HookRegistry.Hook hook) {
        int wanted = storeOpcode(hook.targetDescriptor);
        List<AbstractInsnNode> found = new ArrayList<AbstractInsnNode>();
        int seen = 0;
        for (AbstractInsnNode insn = target.instructions.getFirst();
             insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != wanted) {
                continue;
            }
            if (hook.ordinal < 0 || seen == hook.ordinal) {
                found.add(insn);
            }
            seen++;
        }
        if (found.isEmpty()) {
            log.offer("  wanted store #" + hook.ordinal + " of " + hook.targetDescriptor
                    + " in " + hook.method + ", saw " + seen);
        }
        return found;
    }
    private int storeOpcode(String descriptor) {
        if (descriptor == null || descriptor.length() == 0) {
            return Opcodes.ASTORE;
        }
        switch (descriptor.charAt(0)) {
            case 'Z':
            case 'B':
            case 'C':
            case 'S':
            case 'I': return Opcodes.ISTORE;
            case 'J': return Opcodes.LSTORE;
            case 'F': return Opcodes.FSTORE;
            case 'D': return Opcodes.DSTORE;
            default: return Opcodes.ASTORE;
        }
    }
    private List<MethodInsnNode> findCalls(MethodNode target, HookRegistry.Hook hook) {
        String[] names = hook.targetRemap
                ? MappingBridge.methodNames(hook.targetNameOwner, hook.targetMember)
                : new String[]{hook.targetMember};
        Set<String> owners = ownerNames(hook.targetOwner);
        Set<String> descriptors = MappingBridge.descriptors(hook.targetDescriptor);
        List<MethodInsnNode> found = new ArrayList<MethodInsnNode>();
        int seen = 0;
        for (AbstractInsnNode insn = target.instructions.getFirst();
             insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode)) {
                continue;
            }
            MethodInsnNode call = (MethodInsnNode) insn;
            if (!owners.contains(call.owner)) {
                continue;
            }
            if (hook.targetDescriptor != null && !descriptors.contains(call.desc)) {
                continue;
            }
            for (String candidate : names) {
                if (candidate != null && candidate.equals(call.name)) {
                    if (hook.ordinal < 0 || seen == hook.ordinal) {
                        found.add(call);
                    }
                    seen++;
                    break;
                }
            }
        }
        if (found.isEmpty()) {
            log.offer("  wanted " + java.util.Arrays.toString(names) + hook.targetDescriptor
                    + " on any of " + owners);

            log.offer("  the method calls: " + callsIn(target));
        }
        return found;
    }
    private MethodNode delegateOf(ClassNode node, MethodNode forwarder) {
        MethodInsnNode call = null;
        for (AbstractInsnNode insn = forwarder.instructions.getFirst();
             insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();
            if (op < 0) {
                continue;
            }
            if (insn instanceof MethodInsnNode) {
                if (call != null) {
                    return null;
                }
                call = (MethodInsnNode) insn;
                continue;
            }
            if (insn instanceof VarInsnNode || insn instanceof InsnNode
                    || insn instanceof IntInsnNode || insn instanceof LdcInsnNode
                    || insn instanceof TypeInsnNode) {
                continue;
            }
            return null;
        }
        if (call == null || !call.owner.equals(node.name)) {
            return null;
        }
        for (MethodNode candidate : node.methods) {
            if (candidate.name.equals(call.name) && candidate.desc.equals(call.desc)
                    && candidate != forwarder) {
                return candidate;
            }
        }
        return null;
    }
    private String callbackDescriptor(HookRegistry.Hook hook) {
        return Obfuscation.mapDescriptor(hook.callbackDescriptor);
    }
    private String callsIn(MethodNode target) {
        Set<String> distinct = new java.util.LinkedHashSet<String>();
        for (AbstractInsnNode insn = target.instructions.getFirst();
             insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) insn;
                distinct.add(call.owner + "." + call.name + call.desc);
            }
        }
        if (distinct.isEmpty()) {
            return "nothing at all";
        }
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (String call : distinct) {
            if (shown == 24) {
                out.append(" ... and ").append(distinct.size() - shown).append(" more");
                break;
            }
            out.append(shown == 0 ? "" : ", ").append(call);
            shown++;
        }
        return out.toString();
    }
    private List<FieldInsnNode> findFieldAccesses(MethodNode target, HookRegistry.Hook hook) {
        String[] names = hook.targetRemap
                ? MappingBridge.fieldNames(hook.targetNameOwner, hook.targetMember)
                : new String[]{hook.targetMember};
        Set<String> owners = ownerNames(hook.targetOwner);
        Set<String> descriptors = MappingBridge.descriptors(hook.targetDescriptor);
        List<FieldInsnNode> found = new ArrayList<FieldInsnNode>();
        int seen = 0;
        for (AbstractInsnNode insn = target.instructions.getFirst();
             insn != null; insn = insn.getNext()) {
            if (!(insn instanceof FieldInsnNode)) {
                continue;
            }
            FieldInsnNode access = (FieldInsnNode) insn;
            if (!owners.contains(access.owner)) {
                continue;
            }
            if (hook.targetDescriptor != null && !descriptors.contains(access.desc)) {
                continue;
            }
            for (String candidate : names) {
                if (candidate != null && candidate.equals(access.name)) {
                    if (hook.ordinal < 0 || seen == hook.ordinal) {
                        found.add(access);
                    }
                    seen++;
                    break;
                }
            }
        }
        if (found.isEmpty()) {
            log.offer("  wanted field " + java.util.Arrays.toString(names) + " "
                    + hook.targetDescriptor + " on any of " + owners);
        }
        return found;
    }
    private Set<String> ownerNames(String mcpClass) {
        Set<String> names = new java.util.LinkedHashSet<String>();
        names.add(mcpClass.replace('.', '/'));
        String notch = MappingBridge.notchClass(mcpClass);
        if (notch != null) {
            names.add(notch.replace('.', '/'));
        }
        return names;
    }

    private static boolean cancellableHere(HookRegistry.Hook hook) {
        return !hook.cancellable
                || hook.position == HookRegistry.Position.HEAD
                || hook.position == HookRegistry.Position.RETURN;
    }

    private InsnList callback(MethodNode target, HookRegistry.Hook hook) {
        InsnList list = new InsnList();
        boolean isStatic = (target.access & Opcodes.ACC_STATIC) != 0;
        Type[] args = Type.getArgumentTypes(target.desc);
        for (String piece : hook.argSpec.split(",")) {
            piece = piece.trim();
            if (piece.length() == 0) {
                continue;
            }
            if (piece.equals("this")) {
                list.add(new VarInsnNode(Opcodes.ALOAD, 0));
                continue;
            }
            int index = Integer.parseInt(piece);
            int slot = isStatic ? 0 : 1;
            for (int i = 0; i < index; i++) {
                slot += args[i].getSize();
            }
            list.add(new VarInsnNode(args[index].getOpcode(Opcodes.ILOAD), slot));
        }
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hook.callbackOwner,
                hook.callbackName, callbackDescriptor(hook), false));
        if (hook.cancellable) {
            Type returned = Type.getReturnType(target.desc);
            if (returned.getSort() == Type.VOID) {
                cancelVoid(list);
            } else {
                cancelWithValue(list, returned);
            }
        }
        return list;
    }
    private void cancelVoid(InsnList list) {
        LabelNode carryOn = new LabelNode();
        list.add(new JumpInsnNode(Opcodes.IFEQ, carryOn));
        list.add(new InsnNode(Opcodes.RETURN));
        list.add(carryOn);
        list.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
    }
    private void cancelWithValue(InsnList list, Type returned) {
        boolean reference = returned.getSort() == Type.OBJECT || returned.getSort() == Type.ARRAY;
        LabelNode carryOn = new LabelNode();
        list.add(new InsnNode(Opcodes.DUP));
        list.add(new JumpInsnNode(Opcodes.IFNULL, carryOn));
        if (reference) {
            list.add(new TypeInsnNode(Opcodes.CHECKCAST, "[Ljava/lang/Object;"));
            list.add(new InsnNode(Opcodes.ICONST_0));
            list.add(new InsnNode(Opcodes.AALOAD));
            list.add(new TypeInsnNode(Opcodes.CHECKCAST, returned.getInternalName()));
        } else {
            list.add(unbox(returned));
        }
        list.add(new InsnNode(returned.getOpcode(Opcodes.IRETURN)));
        list.add(carryOn);
        list.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1,
                new Object[]{"java/lang/Object"}));
        list.add(new InsnNode(Opcodes.POP));
    }
    private InsnList unbox(Type returned) {
        InsnList list = new InsnList();
        String box;
        String method;
        switch (returned.getSort()) {
            case Type.BOOLEAN: box = "java/lang/Boolean"; method = "booleanValue"; break;
            case Type.CHAR: box = "java/lang/Character"; method = "charValue"; break;
            case Type.BYTE: box = "java/lang/Byte"; method = "byteValue"; break;
            case Type.SHORT: box = "java/lang/Short"; method = "shortValue"; break;
            case Type.INT: box = "java/lang/Integer"; method = "intValue"; break;
            case Type.FLOAT: box = "java/lang/Float"; method = "floatValue"; break;
            case Type.LONG: box = "java/lang/Long"; method = "longValue"; break;
            case Type.DOUBLE: box = "java/lang/Double"; method = "doubleValue"; break;
            default:
                list.add(new TypeInsnNode(Opcodes.CHECKCAST, returned.getInternalName()));
                return list;
        }
        list.add(new TypeInsnNode(Opcodes.CHECKCAST, box));
        list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, box, method,
                "()" + returned.getDescriptor(), false));
        return list;
    }
}
