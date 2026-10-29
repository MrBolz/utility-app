package io.github.mrbolz.utilityapp.network;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Connects to several TCP ports of one host at once and reports which ones answered. */
final class TcpProbe {
    static final class Result {
        final Set<Integer> open = new TreeSet<>();
        /** True when the host answered at all, either by accepting or by refusing a connection. */
        boolean alive;
        /** Time until the first answer, or -1. */
        long latencyMs = -1;
    }

    private TcpProbe() {}

    static Result probe(InetAddress address, int[] ports, int timeoutMs) {
        Result result = new Result();
        long start = System.nanoTime();
        List<SocketChannel> channels = new ArrayList<>();
        try (Selector selector = Selector.open()) {
            for (int port : ports) {
                SocketChannel channel = SocketChannel.open();
                channels.add(channel);
                channel.configureBlocking(false);
                try {
                    if (channel.connect(new InetSocketAddress(address, port))) answered(result, port, true, start);
                    else channel.register(selector, SelectionKey.OP_CONNECT, port);
                } catch (IOException e) {
                    if (isRefused(e)) answered(result, port, false, start);
                }
            }
            long deadline = start + timeoutMs * 1_000_000L;
            while (!selector.keys().isEmpty()) {
                long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0 || selector.select(remainingMs) == 0) {
                    if (System.nanoTime() >= deadline) break;
                    continue;
                }
                for (SelectionKey key : selector.selectedKeys()) {
                    int port = (Integer) key.attachment();
                    try {
                        if (((SocketChannel) key.channel()).finishConnect()) answered(result, port, true, start);
                    } catch (IOException e) {
                        if (isRefused(e)) answered(result, port, false, start);
                    }
                    key.cancel();
                }
                selector.selectedKeys().clear();
            }
        } catch (IOException ignored) {
            // Selector failure: report what was found so far.
        } finally {
            for (SocketChannel channel : channels) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                    // Nothing to do.
                }
            }
        }
        return result;
    }

    private static void answered(Result result, int port, boolean open, long start) {
        if (open) result.open.add(port);
        if (!result.alive) result.latencyMs = (System.nanoTime() - start) / 1_000_000L;
        result.alive = true;
    }

    /**
     * A refused connection means a host is there but the port is closed. Android reports every
     * connect error as ConnectException, so the message tells "refused" apart from "unreachable".
     */
    static boolean isRefused(IOException e) {
        String message = e.getMessage();
        return e instanceof ConnectException && message != null && message.toLowerCase().contains("refused");
    }
}
