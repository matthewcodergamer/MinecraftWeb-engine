import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.teavm.model.BasicBlock;
import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.ClassHolderTransformerContext;
import org.teavm.model.ElementModifier;
import org.teavm.model.Instruction;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodHolder;
import org.teavm.model.MethodReference;
import org.teavm.model.Program;
import org.teavm.model.ValueType;
import org.teavm.model.Variable;
import org.teavm.model.instructions.ExitInstruction;
import org.teavm.model.instructions.InvocationType;
import org.teavm.model.instructions.InvokeInstruction;

/**
 * Compile-time bytecode adaptation of the original client/libraries, driven by patches/rules.txt.
 *
 * REDIRECT owner name+desc -> newOwner newName
 *     Rewrites every call site to a static method. For instance methods the receiver becomes the
 *     first argument. Used for JDK APIs that TeaVM's class library lacks.
 * BODY owner name+desc -> newOwner newName
 *     Replaces one method body with a call to a static method (receiver first for instance methods).
 *     Used for code paths that need native facilities (Unsafe, OSHI, JNI) in the original.
 */
public class PortTransformer implements ClassHolderTransformer {
    private static final Map<String, MethodReference> REDIRECTS = new HashMap<>();
    private static final Map<String, Map<MethodDescriptor, MethodReference>> BODIES = new HashMap<>();
    private static final Map<String, Integer> APPLIED = new HashMap<>();
    private static final java.util.Set<String> STUB_CLASSES = new java.util.HashSet<>();
    private static final Map<String, java.util.Set<MethodDescriptor>> STUBS = new HashMap<>();
    private static final java.util.Set<String> ZERO_NATIVES = new java.util.HashSet<>();

    static {
        String file = System.getProperty("port.rules");
        if (file != null) {
            try {
                for (String raw : Files.readAllLines(Path.of(file))) {
                    String line = raw.strip();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    String[] p = line.split("\\s+");
                    if (p[0].equals("STUB_CLASS") && p.length == 2) {
                        STUB_CLASSES.add(p[1]);
                        continue;
                    }
                    if (p[0].equals("ZERO_NATIVES") && p.length == 2) {
                        ZERO_NATIVES.add(p[1]);
                        continue;
                    }
                    if (p[0].equals("STUB") && p.length == 3) {
                        STUBS.computeIfAbsent(p[1], k -> new java.util.HashSet<>()).add(MethodDescriptor.parse(p[2]));
                        continue;
                    }
                    if (p.length != 6 || !p[3].equals("->")) {
                        throw new IllegalArgumentException("bad rule: " + line);
                    }
                    MethodDescriptor desc = MethodDescriptor.parse(p[2]);
                    boolean isStatic = p[0].endsWith("_STATIC");
                    String kind = p[0].replace("_STATIC", "");
                    ValueType[] sig = desc.getSignature();
                    ValueType[] target = sig;
                    if (!isStatic) {
                        target = new ValueType[sig.length + 1];
                        target[0] = ValueType.object(p[1]);
                        System.arraycopy(sig, 0, target, 1, sig.length);
                    }
                    MethodReference to = new MethodReference(p[4], p[5], target);
                    if (kind.equals("REDIRECT")) {
                        REDIRECTS.put(p[1] + "." + desc, to);
                    } else if (kind.equals("BODY")) {
                        BODIES.computeIfAbsent(p[1], k -> new HashMap<>()).put(desc, to);
                    } else {
                        throw new IllegalArgumentException("bad rule kind: " + line);
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                synchronized (APPLIED) {
                    APPLIED.forEach((k, v) -> System.out.println("PATCH " + k + " x" + v));
                }
            }));
        }
    }

    private static void count(String key) {
        synchronized (APPLIED) {
            APPLIED.merge(key, 1, Integer::sum);
        }
    }

    @Override
    public void transformClass(ClassHolder cls, ClassHolderTransformerContext context) {
        Map<MethodDescriptor, MethodReference> bodies = BODIES.get(cls.getName());
        boolean stubAll = STUB_CLASSES.contains(cls.getName());
        java.util.Set<MethodDescriptor> stubs = STUBS.get(cls.getName());
        boolean zeroNatives = ZERO_NATIVES.contains(cls.getName());
        for (MethodHolder method : cls.getMethods()) {
            if (zeroNatives && method.hasModifier(ElementModifier.NATIVE)) {
                zeroBody(method);
                count("ZERO_NATIVE " + cls.getName() + "." + method.getDescriptor());
                continue;
            }
            if ((stubAll || (stubs != null && stubs.contains(method.getDescriptor())))
                    && !method.hasModifier(ElementModifier.ABSTRACT)) {
                stubBody(method, cls.getName());
                count("STUB " + cls.getName() + "." + method.getDescriptor());
                continue;
            }
            if (bodies != null) {
                MethodReference to = bodies.get(method.getDescriptor());
                if (to != null) {
                    replaceBody(method, to);
                    count("BODY " + cls.getName() + "." + method.getDescriptor());
                    continue;
                }
            }
            Program program = method.getProgram();
            if (program == null || REDIRECTS.isEmpty()) {
                continue;
            }
            for (BasicBlock block : program.getBasicBlocks()) {
                for (Instruction insn : block) {
                    if (insn instanceof InvokeInstruction) {
                        InvokeInstruction invoke = (InvokeInstruction) insn;
                        MethodReference ref = invoke.getMethod();
                        MethodReference to = REDIRECTS.get(ref.getClassName() + "." + ref.getDescriptor());
                        if (to != null) {
                            List<Variable> args = new ArrayList<>();
                            if (invoke.getInstance() != null) {
                                args.add(invoke.getInstance());
                            }
                            args.addAll(invoke.getArguments());
                            invoke.setInstance(null);
                            invoke.setType(InvocationType.SPECIAL);
                            invoke.setMethod(to);
                            invoke.setArguments(args.toArray(new Variable[0]));
                            count("REDIRECT " + ref);
                        }
                    }
                }
            }
        }
    }

    /** Body becomes: throw new UnsupportedOperationException("<class>.<method> is unavailable in the browser"). */
    private static void stubBody(MethodHolder method, String owner) {
        Program program = new Program();
        program.createVariable();
        for (int i = 0; i < method.parameterCount(); i++) {
            program.createVariable();
        }
        BasicBlock block = program.createBasicBlock();
        Variable msg = program.createVariable();
        org.teavm.model.instructions.StringConstantInstruction str =
                new org.teavm.model.instructions.StringConstantInstruction();
        str.setConstant(owner + "." + method.getName() + " is unavailable in the browser port");
        str.setReceiver(msg);
        Variable ex = program.createVariable();
        org.teavm.model.instructions.ConstructInstruction construct =
                new org.teavm.model.instructions.ConstructInstruction();
        construct.setType("java.lang.UnsupportedOperationException");
        construct.setReceiver(ex);
        InvokeInstruction init = new InvokeInstruction();
        init.setType(InvocationType.SPECIAL);
        init.setInstance(ex);
        init.setMethod(new MethodReference("java.lang.UnsupportedOperationException", "<init>",
                ValueType.object("java.lang.String"), ValueType.VOID));
        init.setArguments(msg);
        org.teavm.model.instructions.RaiseInstruction raise = new org.teavm.model.instructions.RaiseInstruction();
        raise.setException(ex);
        block.add(str);
        block.add(construct);
        block.add(init);
        block.add(raise);
        method.getModifiers().remove(ElementModifier.NATIVE);
        method.setProgram(program);
    }

    /** Native method that returns the zero value of its type (native libraries that are never really used). */
    private static void zeroBody(MethodHolder method) {
        Program program = new Program();
        program.createVariable();
        for (int i = 0; i < method.parameterCount(); i++) {
            program.createVariable();
        }
        BasicBlock block = program.createBasicBlock();
        ExitInstruction exit = new ExitInstruction();
        ValueType rt = method.getResultType();
        if (rt != ValueType.VOID) {
            Variable v = program.createVariable();
            if (rt instanceof ValueType.Primitive) {
                switch (((ValueType.Primitive) rt).getKind()) {
                    case LONG: {
                        org.teavm.model.instructions.LongConstantInstruction c = new org.teavm.model.instructions.LongConstantInstruction();
                        c.setReceiver(v);
                        block.add(c);
                        break;
                    }
                    case FLOAT: {
                        org.teavm.model.instructions.FloatConstantInstruction c = new org.teavm.model.instructions.FloatConstantInstruction();
                        c.setReceiver(v);
                        block.add(c);
                        break;
                    }
                    case DOUBLE: {
                        org.teavm.model.instructions.DoubleConstantInstruction c = new org.teavm.model.instructions.DoubleConstantInstruction();
                        c.setReceiver(v);
                        block.add(c);
                        break;
                    }
                    default: {
                        org.teavm.model.instructions.IntegerConstantInstruction c = new org.teavm.model.instructions.IntegerConstantInstruction();
                        c.setReceiver(v);
                        block.add(c);
                        break;
                    }
                }
            } else {
                org.teavm.model.instructions.NullConstantInstruction c = new org.teavm.model.instructions.NullConstantInstruction();
                c.setReceiver(v);
                block.add(c);
            }
            exit.setValueToReturn(v);
        }
        block.add(exit);
        method.getModifiers().remove(ElementModifier.NATIVE);
        method.setProgram(program);
    }

    private static void replaceBody(MethodHolder method, MethodReference to) {
        Program program = new Program();
        Variable self = program.createVariable();
        List<Variable> args = new ArrayList<>();
        if (!method.hasModifier(ElementModifier.STATIC)) {
            args.add(self);
        }
        for (int i = 0; i < method.parameterCount(); i++) {
            args.add(program.createVariable());
        }
        BasicBlock block = program.createBasicBlock();
        InvokeInstruction invoke = new InvokeInstruction();
        invoke.setType(InvocationType.SPECIAL);
        invoke.setMethod(to);
        invoke.setArguments(args.toArray(new Variable[0]));
        ExitInstruction exit = new ExitInstruction();
        if (method.getResultType() != ValueType.VOID) {
            Variable result = program.createVariable();
            invoke.setReceiver(result);
            exit.setValueToReturn(result);
        }
        block.add(invoke);
        block.add(exit);
        method.getModifiers().remove(ElementModifier.NATIVE);
        method.setProgram(program);
    }
}
