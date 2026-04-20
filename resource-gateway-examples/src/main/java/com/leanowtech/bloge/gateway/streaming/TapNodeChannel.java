package com.leanowtech.bloge.gateway.streaming;

import com.leanowtech.bloge.core.stream.NodeChannel;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * A delegating wrapper around {@link NodeChannel} that executes a side-effect callback
 * on every {@link #send} call while preserving normal channel semantics.
 *
 * <p>This is useful for "tapping" the stream of chunks flowing through a channel —
 * for example, to emit SSE events, write audit logs, or publish metrics — without
 * altering the data or the channel lifecycle.
 *
 * <h3>Semantics preserved</h3>
 * <ul>
 *   <li>The tap callback is invoked <em>after</em> the chunk is successfully forwarded
 *       to the delegate channel. If the delegate blocks (buffer full), the tap callback
 *       is delayed accordingly.</li>
 *   <li>{@link #close()} and {@link #closeWithError(Exception)} delegate directly to the
 *       underlying channel — the tap does not interfere with lifecycle.</li>
 * </ul>
 *
 * @param <T> the chunk type
 */
public class TapNodeChannel<T> extends NodeChannel<T> {

    private final NodeChannel<T> delegate;
    private final Consumer<T> tapCallback;

    /**
     * Creates a tap-wrapped channel.
     *
     * @param delegate    the underlying channel to forward chunks to
     * @param tapCallback a side-effect callback invoked with each chunk after it is sent
     *                    to the delegate; must not be {@code null}
     */
    public TapNodeChannel(NodeChannel<T> delegate, Consumer<T> tapCallback) {
        super(1); // minimal buffer — we delegate immediately
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.tapCallback = Objects.requireNonNull(tapCallback, "tapCallback");
    }

    /**
     * Forwards the chunk to the delegate channel and then invokes the tap callback.
     *
     * @param chunk the data chunk to send
     * @throws InterruptedException if the thread is interrupted while blocking on the delegate
     */
    @Override
    public void send(T chunk) throws InterruptedException {
        delegate.send(chunk);
        tapCallback.accept(chunk);
    }

    /** Delegates to the underlying channel. */
    @Override
    public void close() {
        delegate.close();
    }

    /** Delegates to the underlying channel. */
    @Override
    public void closeWithError(Exception error) {
        delegate.closeWithError(error);
    }
}
