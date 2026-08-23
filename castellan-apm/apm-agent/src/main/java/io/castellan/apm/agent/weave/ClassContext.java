package io.castellan.apm.agent.weave;

import java.util.List;

/**
 * The class-level facts a {@link MethodInstrumentationRule} needs to decide whether it applies at
 * all, gathered once per class from the cheap {@link ProbeClassVisitor} pass rather than re-parsed
 * per method. Deliberately carries only <em>directly declared</em> {@code superInternalName} /
 * {@code interfaceInternalNames} — see {@link ProbeClassVisitor}'s class javadoc for why this
 * transformer does not walk the class hierarchy to discover transitively implemented interfaces.
 */
public record ClassContext(String internalName, String superInternalName, List<String> interfaceInternalNames) {

    /** The class's simple (unqualified) name, e.g. {@code "OrderController"} for {@code com/example/OrderController}. */
    public String simpleName() {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? internalName : internalName.substring(slash + 1);
    }
}
