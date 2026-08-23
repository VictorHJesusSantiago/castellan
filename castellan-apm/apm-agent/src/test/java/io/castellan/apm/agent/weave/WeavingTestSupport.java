package io.castellan.apm.agent.weave;

import java.io.IOException;
import java.io.InputStream;

/**
 * Loads a test fixture class's bytecode from the test classpath and defines the possibly-woven
 * bytes into a brand-new {@link ClassLoader} — the "no full agent-attach needed" test strategy the
 * task brief calls the required minimum: {@link CastellanClassFileTransformer#transform} is just a
 * {@code byte[] -> byte[]} function, so calling it directly and loading the result is a complete,
 * realistic test of the weaving logic without needing a real {@code -javaagent} attach (that is
 * additionally covered, as a stronger addition, by {@code CastellanAgentAttachTest}).
 *
 * <p>A fresh {@link ClassLoader} per test is required, not incidental: the JVM does not allow
 * redefining a class's bytecode for a {@link Class} object that is already loaded under a
 * different {@link ClassLoader} identity via a plain {@code defineClass} call — loading the woven
 * bytes under a new loader sidesteps that entirely and also guarantees each test starts from a
 * clean slate.
 */
public final class WeavingTestSupport {

    private WeavingTestSupport() {
    }

    public static byte[] readClassBytes(Class<?> fixtureClass) {
        String resourcePath = fixtureClass.getName().replace('.', '/') + ".class";
        try (InputStream in = fixtureClass.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("could not locate class bytes for " + resourcePath);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read class bytes for " + resourcePath, e);
        }
    }

    public static Class<?> defineInFreshClassLoader(Class<?> fixtureClass, byte[] classBytes) {
        ClassLoader freshLoader = new ClassLoader(fixtureClass.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.equals(fixtureClass.getName())) {
                    return defineClass(name, classBytes, 0, classBytes.length);
                }
                throw new ClassNotFoundException(name);
            }

            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(fixtureClass.getName())) {
                    Class<?> defined = findClass(name);
                    if (resolve) {
                        resolveClass(defined);
                    }
                    return defined;
                }
                return super.loadClass(name, resolve);
            }
        };
        try {
            return Class.forName(fixtureClass.getName(), true, freshLoader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
