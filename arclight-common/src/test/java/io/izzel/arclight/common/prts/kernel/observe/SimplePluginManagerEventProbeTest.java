/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import org.bukkit.Server;
import java.util.logging.Logger;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.RemoteServerCommandEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.SimplePluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives the real SimplePluginManager.callEvent -> private fireEvent -> HandlerList ->
 * RegisteredListener.callEvent chain. A synchronous test event avoids Bukkit's async guard; root
 * classification is the RCON bridge's boolean, while the probe/snapshot logic is the product one. */
class SimplePluginManagerEventProbeTest {

    private Server server;
    private SimplePluginManager manager;
    private Plugin plugin;
    private Listener listener;

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getLogger("acc26-test");
        server = (Server) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{Server.class}, (proxy, method, args) -> {
                if ("isPrimaryThread".equals(method.getName())) {
                    return Boolean.TRUE;
                }
                if ("getLogger".equals(method.getName())) {
                    return logger;
                }
                return defaultValue(method.getReturnType());
            });
        manager = new SimplePluginManager(server, new SimpleCommandMap(server));
        listener = (Listener) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{Listener.class}, (proxy, method, args) -> null);
        PluginDescriptionFile description = new PluginDescriptionFile("test", "1.0", "test.Main");
        plugin = (Plugin) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{Plugin.class}, (proxy, method, args) -> {
                if ("equals".equals(method.getName())) {
                    return proxy == args[0];
                }
                if ("hashCode".equals(method.getName())) {
                    return System.identityHashCode(proxy);
                }
                if ("toString".equals(method.getName())) {
                    return "acc26-test-plugin";
                }
                if ("isEnabled".equals(method.getName())) {
                    return Boolean.TRUE;
                }
                if ("getDescription".equals(method.getName())) {
                    return description;
                }
                return defaultValue(method.getReturnType());
            });
        BukkitEventOrderProbe.install();
    }

    @AfterEach
    void tearDown() {
        BukkitEventOrderProbe.uninstall();
        for (HandlerList handlers : new HandlerList[]{SyncTestEvent.getHandlerList(),
            RemoteServerCommandEvent.getHandlerList()}) {
            if (handlers == null) {
                continue;
            }
            for (RegisteredListener registered : handlers.getRegisteredListeners()) {
                if (registered.getListener() == listener) {
                    handlers.unregister(registered);
                }
            }
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == double.class) {
            return 0.0d;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }

    private RegisteredListener mine() {
        for (RegisteredListener registered : SyncTestEvent.getHandlerList()
            .getRegisteredListeners()) {
            if (registered.getListener() == listener) {
                return registered;
            }
        }
        throw new AssertionError("listener not registered");
    }

    @Test
    void theRealRemoteServerCommandEventIsTheRootOfTheTrace() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<RegisteredListener> self = new AtomicReference<>();
        manager.registerEvent(RemoteServerCommandEvent.class, listener, EventPriority.NORMAL,
            (registered, event) -> {
                BukkitEventOrderProbe.invokedCurrent(self.get());
                calls.incrementAndGet();
            }, plugin);
        for (RegisteredListener registered : RemoteServerCommandEvent.getHandlerList()
            .getRegisteredListeners()) {
            if (registered.getListener() == listener) {
                self.set(registered);
            }
        }
        RemoteConsoleCommandSender sender = (RemoteConsoleCommandSender) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{RemoteConsoleCommandSender.class},
            (proxy, method, args) -> defaultValue(method.getReturnType()));
        RemoteServerCommandEvent event = new RemoteServerCommandEvent(sender, "say hi");
        RegisteredListener[] snapshot = RemoteServerCommandEvent.getHandlerList()
            .getRegisteredListeners();

        BukkitEventOrderProbe.begin(snapshot, event instanceof RemoteServerCommandEvent);
        manager.callEvent(event);
        BukkitEventOrderProbe.end(false);

        assertEquals(1, calls.get());
        assertEquals(1, BukkitEventOrderProbe.reading().orElseThrow().nestingDepth());
        assertEquals(0L, BukkitEventOrderProbe.reading().orElseThrow().orderViolation());
    }

    @Test
    void aSameThreadRemoteCommandChildAddsOneNestingLevel() {
        AtomicBoolean nested = new AtomicBoolean();
        AtomicReference<RegisteredListener> self = new AtomicReference<>();
        manager.registerEvent(RemoteServerCommandEvent.class, listener, EventPriority.NORMAL,
            (registered, event) -> {
                BukkitEventOrderProbe.invokedCurrent(self.get());
                if (nested.compareAndSet(false, true)) {
                    RegisteredListener[] childSnapshot = RemoteServerCommandEvent.getHandlerList()
                        .getRegisteredListeners();
                    BukkitEventOrderProbe.begin(childSnapshot, false);
                    manager.callEvent(remoteEvent("child"));
                    BukkitEventOrderProbe.end(false);
                }
            }, plugin);
        for (RegisteredListener registered : RemoteServerCommandEvent.getHandlerList()
            .getRegisteredListeners()) {
            if (registered.getListener() == listener) {
                self.set(registered);
            }
        }
        RegisteredListener[] snapshot = RemoteServerCommandEvent.getHandlerList()
            .getRegisteredListeners();

        BukkitEventOrderProbe.begin(snapshot, true);
        manager.callEvent(remoteEvent("root"));
        BukkitEventOrderProbe.end(false);

        assertEquals(2, BukkitEventOrderProbe.reading().orElseThrow().nestingDepth());
        assertEquals(0L, BukkitEventOrderProbe.reading().orElseThrow().orderViolation());
    }

    private RemoteServerCommandEvent remoteEvent(String command) {
        RemoteConsoleCommandSender sender = (RemoteConsoleCommandSender) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{RemoteConsoleCommandSender.class},
            (proxy, method, args) -> defaultValue(method.getReturnType()));
        return new RemoteServerCommandEvent(sender, command);
    }

    @Test
    void realFireEventRunsTheRegisteredListenerOnceAndProbeSeesTheOrder() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<RegisteredListener> self = new AtomicReference<>();
        manager.registerEvent(SyncTestEvent.class, listener, EventPriority.NORMAL,
            (registered, event) -> {
                BukkitEventOrderProbe.invokedCurrent(self.get());
                calls.incrementAndGet();
            }, plugin);
        self.set(mine());
        RegisteredListener[] snapshot = SyncTestEvent.getHandlerList().getRegisteredListeners();

        BukkitEventOrderProbe.begin(snapshot, true);
        manager.callEvent(new SyncTestEvent());
        BukkitEventOrderProbe.end(false);

        assertEquals(1, calls.get());
        assertEquals(0L, BukkitEventOrderProbe.reading().orElseThrow().orderViolation());
        assertEquals(1, BukkitEventOrderProbe.reading().orElseThrow().nestingDepth());
    }

    @Test
    void cancelAndRewriteStayOnTheRealDispatchPath() {
        AtomicBoolean canceled = new AtomicBoolean();
        AtomicReference<RegisteredListener> self = new AtomicReference<>();
        manager.registerEvent(SyncTestEvent.class, listener, EventPriority.NORMAL,
            (registered, event) -> {
                BukkitEventOrderProbe.invokedCurrent(self.get());
                SyncTestEvent test = (SyncTestEvent) event;
                if (canceled.compareAndSet(false, true)) {
                    test.setCommand("say rewritten");
                    test.setCancelled(true);
                }
            }, plugin);
        self.set(mine());
        RegisteredListener[] snapshot = SyncTestEvent.getHandlerList().getRegisteredListeners();
        SyncTestEvent event = new SyncTestEvent();

        BukkitEventOrderProbe.begin(snapshot, true);
        manager.callEvent(event);
        BukkitEventOrderProbe.end(false);

        assertTrue(canceled.get());
        assertTrue(event.isCancelled());
        assertEquals("say rewritten", event.getCommand());
        assertEquals(0L, BukkitEventOrderProbe.reading().orElseThrow().orderViolation());
    }

    @Test
    void realNestedFireEventReportsTheSameThreadDepth() {
        AtomicBoolean nested = new AtomicBoolean();
        AtomicReference<RegisteredListener> self = new AtomicReference<>();
        manager.registerEvent(SyncTestEvent.class, listener, EventPriority.NORMAL,
            (registered, event) -> {
                BukkitEventOrderProbe.invokedCurrent(self.get());
                if (nested.compareAndSet(false, true)) {
                    RegisteredListener[] childSnapshot = SyncTestEvent.getHandlerList()
                        .getRegisteredListeners();
                    BukkitEventOrderProbe.begin(childSnapshot, false);
                    manager.callEvent(new SyncTestEvent());
                    BukkitEventOrderProbe.end(false);
                }
            }, plugin);
        self.set(mine());
        RegisteredListener[] snapshot = SyncTestEvent.getHandlerList().getRegisteredListeners();

        BukkitEventOrderProbe.begin(snapshot, true);
        manager.callEvent(new SyncTestEvent());
        BukkitEventOrderProbe.end(false);

        assertEquals(2, BukkitEventOrderProbe.reading().orElseThrow().nestingDepth());
        assertEquals(0L, BukkitEventOrderProbe.reading().orElseThrow().orderViolation());
    }

    @Test
    void aListenerExceptionDoesNotTurnTheTraceIntoAFakeZero() {
        manager.registerEvent(SyncTestEvent.class, listener, EventPriority.NORMAL,
            (registered, event) -> {
                throw new IllegalStateException("listener boom");
            }, plugin);
        RegisteredListener[] snapshot = SyncTestEvent.getHandlerList().getRegisteredListeners();

        BukkitEventOrderProbe.begin(snapshot, true);
        manager.callEvent(new SyncTestEvent());
        BukkitEventOrderProbe.end(false);

        assertEquals(1, BukkitEventOrderProbe.reading().orElseThrow().nestingDepth());
        assertEquals(0L, BukkitEventOrderProbe.reading().orElseThrow().orderViolation());
    }

    /** One synchronous test event with a real HandlerList; the RCON bridge classifies the real
     * RemoteServerCommandEvent by identity, while this test drives the same probe/snapshot logic. */
    private static final class SyncTestEvent extends Event implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        private boolean cancelled;
        private String command = "say hi";

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        static HandlerList getHandlerList() {
            return HANDLERS;
        }

        String getCommand() {
            return command;
        }

        void setCommand(String command) {
            this.command = command;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }
    }
}
