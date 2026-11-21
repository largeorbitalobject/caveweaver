package com.geoworld.probe;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.AllowedAddressResolver;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;

public final class GeoWorldMultiplayerProbe implements ClientModInitializer {
    private static final int TARGET_TICKS = Integer.getInteger("geoworld.probe.ticks", 1600);
    private static final double[][] ROUTE_A = {
            {4453.0, 70.0, 2729.0}, {4452.0, 40.0, 2734.0},
            {4448.0, 0.0, 2750.0}, {4448.0, -40.0, 2760.0}
    };
    private static final double[][] ROUTE_B = {
            {4440.0, 20.0, 2770.0}, {4450.0, -50.0, 2760.0},
            {4435.0, 0.0, 2740.0}, {4455.0, 55.0, 2740.0}
    };

    private int connectedTicks;
    private int lifecycleTicks;
    private int routeSteps;
    private boolean connectRequested;
    private boolean connectionDiagnosticWritten;
    private boolean resolverAllowed;
    private boolean finished;

    @Override
    public void onInitializeClient() {
        if (Boolean.getBoolean("geoworld.probe")) {
            ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        }
    }

    private void onTick(MinecraftClient client) {
        if (finished) {
            return;
        }
        try {
            lifecycleTicks++;
            if (client.player == null || client.world == null || client.getNetworkHandler() == null) {
                if (!connectRequested && lifecycleTicks >= 100) {
                    String server = System.getProperty("geoworld.probe.server", "127.0.0.1:25818");
                    ServerAddress address = ServerAddress.parse(server);
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress(
                                address.getAddress(), address.getPort()), 2000);
                    }
                    resolverAllowed = AllowedAddressResolver.DEFAULT.resolve(address).isPresent();
                    Files.writeString(
                            client.runDirectory.toPath().resolve("tcp-probe.json"),
                            "{\n  \"state\": \"CONNECTED\",\n  \"server\": \""
                                    + server + "\",\n  \"resolver_allowed\": "
                                    + resolverAllowed + "\n}\n",
                            StandardCharsets.UTF_8);
                    if (!resolverAllowed) {
                        throw new IOException("allowed address resolver rejected " + server);
                    }
                    client.options.useNativeTransport = false;
                    ServerInfo info = new ServerInfo(
                            "GeoWorld probe", server, ServerInfo.ServerType.OTHER);
                    ConnectScreen.connect(
                            client.currentScreen, client, address, info, true,
                            null);
                    connectRequested = true;
                }
                if (connectRequested && !connectionDiagnosticWritten && lifecycleTicks >= 300) {
                    Files.writeString(
                            client.runDirectory.toPath().resolve("connection-diagnostic.json"),
                            "{\n  \"resolver_allowed\": " + resolverAllowed
                                    + ",\n  \"screen\": \""
                                    + (client.currentScreen == null ? "null"
                                    : client.currentScreen.getClass().getName())
                                    + "\"\n}\n",
                            StandardCharsets.UTF_8);
                    connectionDiagnosticWritten = true;
                }
                return;
            }
            if (client.getServer() != null) {
                throw new IOException("probe joined an integrated server instead of the remote server");
            }
            String name = client.player.getGameProfile().getName();
            connectedTicks++;
            double[][] route = name.endsWith("B") ? ROUTE_B : ROUTE_A;
            if (routeSteps < route.length) {
                double[] point = route[routeSteps];
                double dx = client.player.getX() - point[0];
                double dy = client.player.getY() - point[1];
                double dz = client.player.getZ() - point[2];
                if (dx * dx + dy * dy + dz * dz <= 9.0) {
                    routeSteps++;
                }
            }
            if (connectedTicks >= TARGET_TICKS) {
                if (routeSteps < route.length) {
                    throw new IOException("client observed only " + routeSteps
                            + " of " + route.length + " server-driven waypoints");
                }
                writeReceipt(client, name);
                finished = true;
                client.scheduleStop();
            }
        } catch (Exception error) {
            fail(client, error);
        }
    }

    private void writeReceipt(MinecraftClient client, String name) throws IOException {
        Path result = client.runDirectory.toPath().resolve("multiplayer-probe-receipt.json");
        String payload = String.format(Locale.ROOT,
                "{\n  \"state\": \"DONE\",\n  \"client_name\": \"%s\",\n  \"remote_server\": true,\n  \"connected_ticks\": %d,\n  \"route_steps\": %d,\n  \"x\": %.3f,\n  \"y\": %.3f,\n  \"z\": %.3f,\n  \"finished_at\": \"%s\"\n}\n",
                name, connectedTicks, routeSteps, client.player.getX(), client.player.getY(),
                client.player.getZ(), Instant.now());
        Files.writeString(result, payload, StandardCharsets.UTF_8);
    }

    private void fail(MinecraftClient client, Exception error) {
        try {
            Files.writeString(client.runDirectory.toPath().resolve("multiplayer-probe-failed.txt"),
                    error.toString() + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
        finished = true;
        client.scheduleStop();
    }
}
