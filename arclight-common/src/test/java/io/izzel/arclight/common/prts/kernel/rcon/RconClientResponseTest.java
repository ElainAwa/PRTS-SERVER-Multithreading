/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.rcon;

import net.minecraft.server.ServerInterface;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A real RCON client/socket round-trip: the client keeps its request id across the blocked
 * command and receives the response only after the owner completion is captured. */
class RconClientResponseTest {

    private record Packet(int id, int type, String body) {
    }

    private static void write(OutputStream out, int id, int type, String body) throws Exception {
        byte[] message = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + 4 + message.length + 2)
            .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(4 + 4 + message.length + 2);
        buffer.putInt(id);
        buffer.putInt(type);
        buffer.put(message);
        buffer.put((byte) 0);
        buffer.put((byte) 0);
        out.write(buffer.array());
        out.flush();
    }

    private static Packet read(InputStream in) throws Exception {
        byte[] lengthBytes = in.readNBytes(4);
        assertEquals(4, lengthBytes.length);
        int length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
        byte[] payload = in.readNBytes(length);
        assertEquals(length, payload.length);
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int id = buffer.getInt();
        int type = buffer.getInt();
        String body = new String(payload, 8, payload.length - 10, StandardCharsets.UTF_8);
        return new Packet(id, type, body);
    }

    @Test
    void requestIdAndDeferredResponseStayPairedOverARealSocket() throws Exception {
        RconCommandDeferral deferral = new RconCommandDeferral();
        CountDownLatch commandArrived = new CountDownLatch(1);
        ServerInterface serverInterface = (ServerInterface) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{ServerInterface.class}, (proxy, method, args) -> {
                if ("runCommand".equals(method.getName())) {
                    return deferral.await((String) args[0], new RconCommandDeferral.OwnerUnit() {
                        @Override
                        public void prepare() {
                        }

                        @Override
                        public void dispatch() {
                        }

                        @Override
                        public String capture() {
                            return "deferred-response";
                        }
                    }, 5_000L, commandArrived::countDown);
                }
                return null;
            });
        AtomicReference<Socket> acceptedSocket = new AtomicReference<>();
        CountDownLatch accepted = new CountDownLatch(1);
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> {
                try {
                    Socket socket = listener.accept();
                    acceptedSocket.set(socket);
                    accepted.countDown();
                    Class<?> type = Class.forName("net.minecraft.server.rcon.thread.RconClient");
                    var constructor = type.getDeclaredConstructor(ServerInterface.class, String.class,
                        Socket.class);
                    constructor.setAccessible(true);
                    Object client = constructor.newInstance(serverInterface, "pw", socket);
                    type.getMethod("start").invoke(client);
                } catch (Exception ignored) {
                    accepted.countDown();
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort())) {
                assertTrue(accepted.await(5, TimeUnit.SECONDS));
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();
                write(out, 7, 3, "pw");
                assertEquals(7, read(in).id());
                write(out, 42, 2, "say hi");
                assertTrue(commandArrived.await(5, TimeUnit.SECONDS));
                deferral.pumpOwner();
                Packet response = read(in);
                assertEquals(42, response.id());
                assertEquals(0, response.type());
                assertEquals("deferred-response", response.body());
            }
        } finally {
            Socket legacy = acceptedSocket.get();
            if (legacy != null) {
                legacy.close();
            }
        }
    }
}
