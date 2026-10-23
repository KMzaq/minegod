package com.sande.mythictrpg.party;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.GameProfileRepository;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Services;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.world.level.storage.LevelResource;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.UUID;

/** Supplies the normal name argument/cache path missing from vanilla GameTestServer, without any network repository. */
public final class PartyProfileGameTestFixture implements AutoCloseable {
    private final MinecraftServer server;
    private final Field servicesField;
    private final Services previous;
    private final Services installed;
    private final GameProfileCache cache;
    private boolean closed;

    public PartyProfileGameTestFixture(MinecraftServer server) {
        try {
            Path world = server.getWorldPath(LevelResource.ROOT).toRealPath();
            boolean buildContained = false;
            for (Path part : world) if (part.toString().equals("build")) buildContained = true;
            if (!(server instanceof GameTestServer) || !server.isSameThread() || !buildContained)
                throw new IllegalStateException("Profile fixture requires a build-contained GameTest world and its server thread");
            this.server = server;
            this.servicesField = MinecraftServer.class.getDeclaredField("services");
            servicesField.setAccessible(true);
            this.previous = (Services) servicesField.get(server);
            GameProfileRepository offline = (names, callback) -> {
                for (String name : names) callback.onProfileLookupFailed(name, new IllegalArgumentException("Offline GameTest repository: " + name));
            };
            this.cache = new GameProfileCache(offline, world.resolve("party-profile-cache-" + UUID.randomUUID() + ".json").toFile());
            cache.setExecutor(server);
            this.installed = new Services(previous.sessionService(), previous.servicesKeySet(), offline, cache);
            servicesField.set(server, installed);
            if (server.getProfileCache() != cache) throw new IllegalStateException("GameTest profile cache installation failed");
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw new IllegalStateException("Cannot install isolated GameTest profiles", failure);
        }
    }

    public void add(GameProfile profile) {
        if (closed || !server.isSameThread()) throw new IllegalStateException("Profile fixture is not active on its server thread");
        cache.add(profile);
    }

    @Override public void close() {
        if (closed) return;
        if (!server.isSameThread()) throw new IllegalStateException("Close profile fixture on its server thread");
        try {
            if (servicesField.get(server) != installed) throw new IllegalStateException("Another fixture replaced server services");
            servicesField.set(server, previous);
            cache.clearExecutor();
            closed = true;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot restore original GameTest services", failure);
        }
    }
}
