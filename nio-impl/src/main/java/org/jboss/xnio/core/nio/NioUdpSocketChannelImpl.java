package org.jboss.xnio.core.nio;

import org.jboss.xnio.channels.MultipointReadHandler;
import org.jboss.xnio.channels.MulticastDatagramChannel;
import org.jboss.xnio.channels.UnsupportedOptionException;
import org.jboss.xnio.channels.Configurable;
import org.jboss.xnio.IoHandler;
import org.jboss.xnio.log.Logger;
import java.net.SocketAddress;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;
import java.util.Collections;

/**
 *
 */
public final class NioUdpSocketChannelImpl implements MulticastDatagramChannel {
    private static final Logger log = Logger.getLogger(NioUdpSocketChannelImpl.class);

    private final DatagramChannel datagramChannel;
    private final NioHandle readHandle;
    private final NioHandle writeHandle;
    private final IoHandler<? super MulticastDatagramChannel> handler;

    private final AtomicBoolean callFlag = new AtomicBoolean(false);

    public NioUdpSocketChannelImpl(final NioProvider nioProvider, final DatagramChannel datagramChannel, final IoHandler<? super MulticastDatagramChannel> handler) throws IOException {
        readHandle = nioProvider.addReadHandler(datagramChannel, new ReadHandler());
        writeHandle = nioProvider.addWriteHandler(datagramChannel, new WriteHandler());
        this.datagramChannel = datagramChannel;
        this.handler = handler;
    }

    public SocketAddress getLocalAddress() {
        return datagramChannel.socket().getLocalSocketAddress();
    }

    public boolean receive(final ByteBuffer buffer, final MultipointReadHandler<SocketAddress> readHandler) throws IOException {
        final SocketAddress sourceAddress = datagramChannel.receive(buffer);
        if (sourceAddress != null) {
            if (readHandler != null) {
                readHandler.handle(sourceAddress, null);
            }
            return true;
        } else {
            return false;
        }
    }

    public boolean isOpen() {
        return datagramChannel.isOpen();
    }

    public void close() throws IOException {
        try {
            datagramChannel.close();
        } finally {
            readHandle.cancelKey();
            writeHandle.cancelKey();
            if (!callFlag.getAndSet(true)) {
                handler.handleClose(this);
            }
        }
    }

    public boolean send(final SocketAddress target, final ByteBuffer buffer) throws IOException {
        return datagramChannel.send(buffer, target) != 0;
    }

    public boolean send(final SocketAddress target, final ByteBuffer[] dsts) throws IOException {
        return send(target, dsts, 0, dsts.length);
    }

    public boolean send(final SocketAddress target, final ByteBuffer[] dsts, final int offset, final int length) throws IOException {
        // todo - gather not supported in NIO.1 so we have to fake it...
        long total = 0L;
        for (int i = 0; i < length; i++) {
            total += (long) dsts[offset + i].remaining();
        }
        if (total > (long)Integer.MAX_VALUE) {
            throw new IOException("Source data is too large");
        }
        ByteBuffer buf = ByteBuffer.allocate((int)total);
        for (int i = 0; i < length; i++) {
            buf.put(dsts[offset + i]);
        }
        buf.flip();
        return send(target, buf);
    }

    public void suspendReads() {
        try {
            readHandle.getSelectionKey().interestOps(0).selector().wakeup();
        } catch (CancelledKeyException ex) {
            // ignore
        }
    }

    public void suspendWrites() {
        try {
            writeHandle.getSelectionKey().interestOps(0).selector().wakeup();
        } catch (CancelledKeyException ex) {
            // ignore
        }
    }

    public void resumeReads() {
        try {
            readHandle.getSelectionKey().interestOps(SelectionKey.OP_READ).selector().wakeup();
        } catch (CancelledKeyException ex) {
            // ignore
        }
    }

    public void resumeWrites() {
        try {
            writeHandle.getSelectionKey().interestOps(SelectionKey.OP_WRITE).selector().wakeup();
        } catch (CancelledKeyException ex) {
            // ignore
        }
    }

    public Key join(final InetAddress group, final NetworkInterface iface) throws IOException {
        throw new UnsupportedOperationException("Multicast join");
    }

    public Key join(final InetAddress group, final NetworkInterface iface, final InetAddress source) throws IOException {
        throw new UnsupportedOperationException("Multicast join");
    }

    public Object getOption(final String name) throws UnsupportedOptionException, IOException {
        throw new UnsupportedOptionException("No options supported");
    }

    public Map<String, Class<?>> getOptions() {
        return Collections.emptyMap();
    }

    public Configurable setOption(final String name, final Object value) throws IllegalArgumentException, IOException {
        throw new UnsupportedOptionException("No options supported");
    }

    public final class ReadHandler implements Runnable {

        public void run() {
            IoHandler<? super MulticastDatagramChannel> handler = NioUdpSocketChannelImpl.this.handler;
            final SelectionKey key = readHandle.getSelectionKey();
            if (key.isValid() && key.isReadable()) try {
                handler.handleReadable(NioUdpSocketChannelImpl.this);
            } catch (Throwable t) {
                log.error(t, "Write handler failed");
            }
        }
    }

    public final class WriteHandler implements Runnable {

        public void run() {
            IoHandler<? super MulticastDatagramChannel> handler = NioUdpSocketChannelImpl.this.handler;
            final SelectionKey key = writeHandle.getSelectionKey();
            if (key.isValid() && key.isWritable()) try {
                handler.handleWritable(NioUdpSocketChannelImpl.this);
            } catch (Throwable t) {
                log.error(t, "Write handler failed");
            }
        }
    }
}
