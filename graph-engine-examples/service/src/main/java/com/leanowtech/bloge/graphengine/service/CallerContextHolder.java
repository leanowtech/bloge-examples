package com.leanowtech.bloge.graphengine.service;

/**
 * Thread-local holder for the current {@link CallerContext}.
 *
 * <p>When the holder is empty ({@code null}), the graph-engine service treats the
 * call as a privileged system/internal invocation and bypasses RBAC checks.  HTTP
 * entry-points populate the holder via a servlet filter; embedded callers may set
 * it explicitly.</p>
 */
public final class CallerContextHolder {

    private static final ThreadLocal<CallerContext> HOLDER = new ThreadLocal<>();

    private CallerContextHolder() {
    }

    /**
     * Returns the caller context bound to the current thread, or {@code null}
     * when no context has been set (system/internal call).
     *
     * @return current caller context, or {@code null}
     */
    public static CallerContext current() {
        return HOLDER.get();
    }

    /**
     * Binds a caller context to the current thread.
     *
     * @param context the caller context to set, or {@code null} to clear
     */
    public static void set(CallerContext context) {
        if (context == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(context);
        }
    }

    /**
     * Clears the caller context for the current thread.
     */
    public static void clear() {
        HOLDER.remove();
    }
}
