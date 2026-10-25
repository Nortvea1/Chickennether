package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ClearRender extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> chunkRange = sgGeneral.add(new IntSetting.Builder()
        .name("chunk-range").description("Sweep loaded chunks within this many chunks of you.")
        .defaultValue(32).range(2, 32).sliderRange(2, 32).build());

    private final Setting<Integer> sweepTicks = sgGeneral.add(new IntSetting.Builder()
        .name("sweep-ticks").description("Start a new full sweep every N ticks (20 = every second).")
        .defaultValue(20).range(1, 200).sliderRange(1, 100).build());

    private final Setting<Integer> msPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("ms-per-tick").description("Time budget per tick in milliseconds (higher = faster, more lag).")
        .defaultValue(20).range(1, 45).sliderRange(1, 45).build());

    private final Setting<Integer> maxSize = sgGeneral.add(new IntSetting.Builder()
        .name("max-size").description("Largest sealed pocket (in blocks) that gets filled.")
        .defaultValue(5).range(1, 32).sliderRange(1, 16).build());

    private final Setting<Integer> minY = sgGeneral.add(new IntSetting.Builder()
        .name("min-y").description("Lowest Y to scan.")
        .defaultValue(-64).range(-64, 320).sliderRange(-64, 128).build());

    private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder()
        .name("max-y").description("Highest Y to scan.")
        .defaultValue(64).range(-64, 320).sliderRange(-64, 128).build());

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug").description("Print how many blocks each sweep filled.")
        .defaultValue(false).build());

    private final List<int[]> queue = new ArrayList<>();
    private int cursor;
    private int sinceSweep;
    private int filledThisSweep;

    public ClearRender() {
        super(AddonTemplate.CATEGORY, "clear-render",
            "Fills small sealed air pockets in all loaded chunks with stone (or netherrack in the Nether) on your client.");
    }

    @Override
    public void onActivate() {
        queue.clear();
        cursor = 0;
        sinceSweep = Integer.MAX_VALUE / 2;
        filledThisSweep = 0;
    }

    @Override
    public void onDeactivate() {
        queue.clear();
        cursor = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.level == null || mc.player == null) return;

        sinceSweep++;

        if (cursor >= queue.size()) {
            if (sinceSweep < sweepTicks.get()) return;

            if (debug.get() && filledThisSweep > 0) info("sweep filled %d blocks", filledThisSweep);
            filledThisSweep = 0;
            sinceSweep = 0;
            rebuildQueue();
            if (queue.isEmpty()) return;
        }

        long deadline = System.nanoTime() + msPerTick.get() * 1_000_000L;
        while (cursor < queue.size() && System.nanoTime() < deadline) {
            int[] c = queue.get(cursor++);
            if (!mc.level.hasChunk(c[0], c[1])) continue;
            filledThisSweep += processChunk(c[0], c[1]);
        }
    }

    private void rebuildQueue() {
        queue.clear();
        cursor = 0;

        int pcx = mc.player.blockPosition().getX() >> 4;
        int pcz = mc.player.blockPosition().getZ() >> 4;
        int r = chunkRange.get();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int cx = pcx + dx;
                int cz = pcz + dz;
                if (mc.level.hasChunk(cx, cz)) queue.add(new int[]{cx, cz, dx * dx + dz * dz});
            }
        }

        queue.sort((a, b) -> Integer.compare(a[2], b[2]));
    }

    private int processChunk(int cx, int cz) {
        LevelChunk chunk = mc.level.getChunk(cx, cz);
        int lo = Math.max(minY.get(), mc.level.getMinY());
        int hi = Math.min(maxY.get(), mc.level.getMaxY() - 1);
        int max = maxSize.get();
        int bx = cx << 4;
        int bz = cz << 4;
        int minSection = mc.level.getMinY() >> 4;
        boolean isNether = mc.level.dimension() == Level.NETHER;

        Set<Long> visited = new HashSet<>();
        Set<Long> queued = new HashSet<>();
        List<BlockPos> toFill = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int i = 0; i < chunk.getSectionsCount(); i++) {
            int secMin = (minSection + i) << 4;
            int secMax = secMin + 15;
            if (secMax < lo || secMin > hi) continue;

            LevelChunkSection section = chunk.getSection(i);
            if (section.hasOnlyAir() || !section.maybeHas(st -> st.isAir())) continue;

            int yStart = Math.max(secMin, lo);
            int yEnd = Math.min(secMax, hi);

            for (int y = yStart; y <= yEnd; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        pos.set(bx + x, y, bz + z);
                        long key = pos.asLong();
                        if (visited.contains(key)) continue;
                        if (!chunk.getBlockState(pos).isAir()) continue;

                        List<BlockPos> pocket = floodFill(pos.immutable(), max, visited);
                        if (pocket == null) continue;

                        for (BlockPos p : pocket) {
                            if (queued.add(p.asLong())) toFill.add(p);
                        }
                    }
                }
            }
        }

        for (BlockPos p : toFill) {
            Block block = fillBlockFor(p, isNether);
            mc.level.setBlock(p, block.defaultBlockState(),
                Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }

        return toFill.size();
    }

    private Block fillBlockFor(BlockPos p, boolean isNether) {
        if (isNether) return Blocks.NETHERRACK;
        return p.getY() < 0 ? Blocks.DEEPSLATE : Blocks.STONE;
    }

    private List<BlockPos> floodFill(BlockPos start, int max, Set<Long> visited) {
        List<BlockPos> region = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start.asLong());
        boolean open = false;

        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            region.add(p);

            if (region.size() > max) {
                open = true;
                break;
            }

            for (Direction dir : Direction.values()) {
                BlockPos n = p.relative(dir);
                long key = n.asLong();
                if (seen.contains(key)) continue;

                if (n.getY() < mc.level.getMinY() || n.getY() >= mc.level.getMaxY()) continue;

                if (!mc.level.hasChunkAt(n)) {
                    open = true;
                    break;
                }

                if (mc.level.getBlockState(n).isAir()) {
                    seen.add(key);
                    queue.add(n);
                }
            }

            if (open) break;
        }

        visited.addAll(seen);
        return open ? null : region;
    }
}
