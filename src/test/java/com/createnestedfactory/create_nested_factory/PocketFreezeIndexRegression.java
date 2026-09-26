package com.createnestedfactory.create_nested_factory;

import com.createnestedfactory.create_nested_factory.block.PocketBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;

import java.util.List;

/** Lightweight regression checks for dynamic room bounds and persisted freeze ledgers. */
public final class PocketFreezeIndexRegression {
    private PocketFreezeIndexRegression() {
    }

    public static void main(String[] args) {
        dynamicBoundsFollowTheRoom();
        sharedChunkColumnsRemainHeightAware();
        freezeLedgerRoundTrips();
        roomlessTerminalManifestRoundTrips();
        thawSnapshotsAreStableUntilTheManifestChanges();
    }

    private static void dynamicBoundsFollowTheRoom() {
        PocketBounds bounds = new PocketBounds();
        BlockPos origin = new BlockPos(64, 128, 64);
        bounds.expand(Direction.WEST);
        bounds.expand(Direction.EAST);
        bounds.expand(Direction.SOUTH);
        assertEquals(48, bounds.maxX(origin) - bounds.minX(origin) + 1, "expanded X width");
        assertEquals(32, bounds.maxZ(origin) - bounds.minZ(origin) + 1, "expanded Z width");
    }

    private static void sharedChunkColumnsRemainHeightAware() {
        PocketFreezeSavedData data = new PocketFreezeSavedData();
        PocketFreezeSavedData.FrozenRoom root = room("root", 0, 128, 0, 31, 159, 31);
        PocketFreezeSavedData.FrozenRoom nested = room("nested", 8, 0, 8, 23, 15, 23);
        data.beginFreeze("root", List.of(root));
        assertTrue(data.isFrozen(new BlockPos(12, 140, 12)), "root room position must freeze");
        assertFalse(data.isFrozen(new BlockPos(12, 8, 12)), "same chunk column at nested Y must remain live");
        assertTrue(data.intersectsFrozenRoom(new AABB(30.5, 140, 30.5, 33, 142, 33)),
                "an entity crossing the room edge must freeze as a whole");

        data.beginFreeze("nested", List.of(nested));
        assertTrue(data.isFrozen(new BlockPos(12, 8, 12)), "nested manifest must freeze its own Y range");
    }

    private static void freezeLedgerRoundTrips() {
        PocketFreezeSavedData data = new PocketFreezeSavedData();
        BlockPos tickPos = new BlockPos(3, 130, 4);
        data.beginFreeze("root", List.of(room("root", 0, 128, 0, 15, 143, 15)));
        assertTrue(data.captureTick(tickPos, PocketFreezeSavedData.TickKind.BLOCK,
                "minecraft:stone", 17, "HIGH", 4), "tick must be captured");
        assertTrue(data.captureBlockEvent(tickPos, "minecraft:piston", 1, 2),
                "block event must be captured");
        data.beginFreeze("root", List.of(room("root", 0, 128, 0, 15, 143, 15)));
        data.beginThaw("root");

        CompoundTag saved = data.save(new CompoundTag(), null);
        PocketFreezeSavedData loaded = PocketFreezeSavedData.load(saved, null);
        assertTrue(loaded.isFrozen(tickPos), "loaded thawing manifests must remain frozen");
        PocketFreezeSavedData.FrozenTree restored = loaded.removeTree("root");
        assertEquals(PocketFreezeSavedData.Phase.THAWING, restored.phase(), "persisted phase");
        assertEquals(1, restored.scheduledTicks().size(), "persisted scheduled ticks");
        assertEquals(17L, restored.scheduledTicks().getFirst().remainingDelay(), "remaining delay");
        assertEquals(1, restored.blockEvents().size(), "persisted block events");
    }

    private static void thawSnapshotsAreStableUntilTheManifestChanges() {
        PocketFreezeSavedData data = new PocketFreezeSavedData();
        BlockPos pos = new BlockPos(-1, 130, -1);
        data.beginFreeze("root", List.of(room("root", -16, 128, -16, 15, 143, 15)));
        data.beginThaw("root");

        List<PocketFreezeSavedData.FrozenTree> first = data.thawingTrees();
        List<PocketFreezeSavedData.FrozenTree> second = data.thawingTrees();
        assertSame(first, second, "unchanged thawing tree list must be reused between ticks");
        assertSame(first.getFirst(), second.getFirst(),
                "unchanged thawing tree snapshot must be reused between ticks");

        assertTrue(data.captureBlockEvent(pos, "minecraft:piston", 2, 3),
                "event inside thawing room must remain captured");
        List<PocketFreezeSavedData.FrozenTree> changed = data.thawingTrees();
        assertNotSame(first, changed, "manifest mutation must invalidate the thawing list snapshot");
        assertNotSame(first.getFirst(), changed.getFirst(),
                "manifest mutation must invalidate the tree snapshot");
        assertEquals(1, changed.getFirst().blockEvents().size(),
                "rebuilt thaw snapshot must contain the captured event");
    }

    private static void roomlessTerminalManifestRoundTrips() {
        PocketFreezeSavedData data = new PocketFreezeSavedData();
        data.beginFreeze("terminal", List.of());
        assertTrue(data.hasTree("terminal"), "roomless terminal freeze lease must have a persistent manifest");
        assertFalse(data.isFrozen(BlockPos.ZERO), "roomless terminal manifest must not freeze Pocket origin");
        CompoundTag saved = data.save(new CompoundTag(), null);
        PocketFreezeSavedData loaded = PocketFreezeSavedData.load(saved, null);
        assertTrue(loaded.hasTree("terminal"), "roomless terminal manifest was lost on reload");
        assertTrue(loaded.beginThaw("terminal"), "roomless terminal manifest could not begin thawing");
        assertEquals(0, loaded.thawingTrees().getFirst().rooms().size(),
                "roomless terminal manifest unexpectedly acquired a physical room");
    }

    private static PocketFreezeSavedData.FrozenRoom room(String id, int minX, int minY, int minZ,
                                                          int maxX, int maxY, int maxZ) {
        return new PocketFreezeSavedData.FrozenRoom(id, "minecraft:overworld", BlockPos.ZERO,
                minX, minY, minZ, maxX, maxY, maxZ, 1);
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertFalse(boolean value, String message) {
        assertTrue(!value, message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertSame(Object expected, Object actual, String message) {
        if (expected != actual) throw new AssertionError(message);
    }

    private static void assertNotSame(Object expected, Object actual, String message) {
        if (expected == actual) throw new AssertionError(message);
    }
}
