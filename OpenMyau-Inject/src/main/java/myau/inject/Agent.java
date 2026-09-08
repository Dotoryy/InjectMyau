package myau.inject;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class Agent {
    private static volatile boolean installed;
    private static volatile ClassLoader installedGameLoader;
    private Agent() {
    }
    public static void premain(String args, Instrumentation instrumentation) {
        install(instrumentation, false);
    }
    public static void agentmain(String args, Instrumentation instrumentation) {
        install(instrumentation, true);
    }
    private static synchronized void install(Instrumentation instrumentation, boolean live) {
        if (installed) {
            if (live && installedGameLoader != null && !clientRunning()) {
                log("hooks already present and the client is unloaded -- starting it again");
                startClient(installedGameLoader);
                return;
            }
            log("already installed, ignoring second attach");
            return;
        }
        installed = true;
        log("installing (" + (live ? "attach" : "premain") + ")");

        try {
            java.net.URL self = Agent.class.getProtectionDomain().getCodeSource().getLocation();
            java.io.File jar = new java.io.File(self.toURI());
            if (jar.isFile()) {
                instrumentation.appendToSystemClassLoaderSearch(new java.util.jar.JarFile(jar));
                log("appended to system class path: " + jar.getName());
            }
        } catch (Throwable t) {
            log("could not append self to system class path: " + t);
        }

        Hooks.register();
        log("hooks registered: " + HookRegistry.size());
        HookTransformer transformer = new HookTransformer(instrumentation.getAllLoadedClasses());
        ClassLoader gameLoader = installIntoGameLoader(transformer.targets());
        if (gameLoader == null) {
            for (String line : transformer.drain()) {
                log("  " + line);
            }
            log("ABORTED: the callback class cannot be reached from the loader that");
            log("defined Minecraft. Hooks were NOT installed -- the game is untouched.");
            return;
        }

        instrumentation.addTransformer(transformer, true);
        if (live) {
            List<Class<?>> targets = transformer.targets();
            log("retransforming " + targets.size() + " classes");
            for (Class<?> target : targets) {
                if (!instrumentation.isModifiableClass(target)) {
                    log("NOT MODIFIABLE " + target.getName());
                    continue;
                }
                try {
                    instrumentation.retransformClasses(target);
                } catch (UnmodifiableClassException e) {
                    log("retransform refused " + target.getName() + ": " + e);
                } catch (Throwable t) {
                    log("retransform failed " + target.getName() + ": " + t);
                }
            }
        }
        for (String line : transformer.drain()) {
            log("  " + line);
        }
        List<String> unresolved = new ArrayList<String>(MappingBridge.failures());
        if (!unresolved.isEmpty()) {
            log("unresolved mappings (" + unresolved.size() + "):");
            for (String failure : unresolved) {
                log("  " + failure);
            }
        }
        installedGameLoader = gameLoader;
        if (live) {
            startClient(gameLoader);
        }
    }

    private static boolean clientRunning() {
        try {
            Class<?> bootstrap =
                    Class.forName("myau.inject.Bootstrap", false, installedGameLoader);
            return Boolean.TRUE.equals(bootstrap.getMethod("isStarted").invoke(null));
        } catch (Throwable t) {
            log("cannot ask whether the client is running: " + t);
            return true;
        }
    }
    private static ClassLoader installIntoGameLoader(List<Class<?>> targets) {
        File jar;
        try {
            jar = new File(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Throwable t) {
            log("cannot locate own jar: " + t);
            return null;
        }

        Set<ClassLoader> loaders = new LinkedHashSet<ClassLoader>();
        for (Class<?> target : targets) {
            ClassLoader cl = target.getClassLoader();
            if (cl != null) {
                loaders.add(cl);
            }
        }
        ClassLoader chosen = null;
        for (ClassLoader loader : loaders) {
            if (equip(loader, jar)) {
                chosen = loader;
            }
        }
        return chosen;
    }
    private static boolean equip(ClassLoader loader, File jar) {
        try {
            Method addUrl = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            addUrl.setAccessible(true);
            addUrl.invoke(loader, jar.toURI().toURL());
        } catch (Throwable t) {
            log("cannot add the jar to " + name(loader) + ": " + t);
            return false;
        }
        forgetFailedLookups(loader);
        try {
            Class<?> callbacks = Class.forName("myau.inject.Callbacks", false, loader);
            log("callbacks resolve from " + name(loader)
                    + ", defined by " + name(callbacks.getClassLoader()));
            return true;
        } catch (Throwable t) {
            log("callbacks still unreachable from " + name(loader) + ": " + t);
            return false;
        }
    }

    private static void forgetFailedLookups(ClassLoader loader) {
        for (Class<?> c = loader.getClass(); c != null; c = c.getSuperclass()) {
            Field field;
            try {
                field = c.getDeclaredField("invalidClasses");
            } catch (NoSuchFieldException absent) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(loader);
                if (!(value instanceof Collection)) {
                    return;
                }
                int dropped = 0;
                for (Iterator<?> it = ((Collection<?>) value).iterator(); it.hasNext(); ) {
                    Object entry = it.next();
                    if (entry instanceof String && ((String) entry).startsWith("myau.")) {
                        it.remove();
                        dropped++;
                    }
                }
                if (dropped > 0) {
                    log("dropped " + dropped + " cached lookup failures from " + name(loader));
                }
            } catch (Throwable t) {
                log("could not clear the negative cache on " + name(loader) + ": " + t);
            }
            return;
        }
    }
    private static String name(ClassLoader loader) {
        return loader == null ? "bootstrap" : loader.getClass().getSimpleName();
    }
    private static void startClient(ClassLoader gameLoader) {
        try {
            Class<?> bootstrap = Class.forName("myau.inject.Bootstrap", true, gameLoader);
            bootstrap.getMethod("requestStart").invoke(null);
            log("client construction queued on the "
                    + name(bootstrap.getClassLoader()) + " copy");
        } catch (Throwable t) {
            log("could not queue client construction: " + t);
        }
    }
    static void log(String message) {
        Log.line(message);
    }
}
