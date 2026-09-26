package com.sande.mythictrpg.quest.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Headless software voxel renderer. It never touches client rendering classes or texture files. */
final class StructureVoxelRenderService {
    private static final int MAX_VISUAL_BLOCKS = 12_000;
    private static final int IMAGE_SIZE = 512;

    private StructureVoxelRenderService() {}

    static Scene capture(ServerLevel level, StructureBuildRecord build, StructureSnapshot snapshot) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Voxel capture must run on server thread");
        List<Map.Entry<Long, PlacementRecord>> entries = new ArrayList<>(build.placements().entrySet());
        entries.sort(Map.Entry.comparingByKey());
        int stride = Math.max(1, (int) Math.ceil(entries.size() / (double) MAX_VISUAL_BLOCKS));
        List<Voxel> voxels = new ArrayList<>(Math.min(entries.size(), MAX_VISUAL_BLOCKS));
        StructureSnapshot.Bounds bounds = snapshot.bounds();
        Map<ResourceLocation, Integer> materials = new LinkedHashMap<>();
        for (int index = 0; index < entries.size() && voxels.size() < MAX_VISUAL_BLOCKS; index += stride) {
            BlockPos pos = BlockPos.of(entries.get(index).getKey());
            if (!level.hasChunkAt(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            voxels.add(new Voxel(pos.getX() - bounds.minX(), pos.getY() - bounds.minY(),
                    pos.getZ() - bounds.minZ(), color(id)));
            materials.merge(id, 1, Integer::sum);
        }
        if (voxels.isEmpty()) throw new IllegalArgumentException("No visible structure voxels");
        return new Scene(bounds.width(), bounds.height(), bounds.depth(), List.copyOf(voxels), Map.copyOf(materials));
    }

    static List<StructureVisualEvaluationGateway.RenderedView> render(Scene scene) {
        List<StructureVisualEvaluationGateway.RenderedView> views = new ArrayList<>();
        views.add(view("isometric_ne", renderIsometric(scene, 0)));
        views.add(view("isometric_nw", renderIsometric(scene, 1)));
        views.add(view("isometric_sw", renderIsometric(scene, 2)));
        views.add(view("isometric_se", renderIsometric(scene, 3)));
        views.add(view("top", renderTop(scene)));
        return List.copyOf(views);
    }

    private static StructureVisualEvaluationGateway.RenderedView view(String name, BufferedImage image) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "PNG", output)) throw new IOException("PNG writer unavailable");
            return new StructureVisualEvaluationGateway.RenderedView(name, output.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode structure render", exception);
        }
    }

    private static BufferedImage renderIsometric(Scene scene, int rotation) {
        BufferedImage image = canvas(); Graphics2D graphics = image.createGraphics(); configure(graphics);
        int rotatedWidth = rotation % 2 == 0 ? scene.width() : scene.depth();
        int rotatedDepth = rotation % 2 == 0 ? scene.depth() : scene.width();
        double horizontalUnits = Math.max(2.0D, rotatedWidth + rotatedDepth + 2.0D);
        double verticalUnits = Math.max(2.0D, (rotatedWidth + rotatedDepth) * 0.5D + scene.height() + 2.0D);
        double tile = Math.max(0.45D, Math.min((IMAGE_SIZE - 28.0D) * 2.0D / horizontalUnits,
                (IMAGE_SIZE - 28.0D) * 2.0D / verticalUnits));
        double projectedHeight = (rotatedWidth + rotatedDepth) * tile * 0.25D + scene.height() * tile * 0.5D;
        double originX = IMAGE_SIZE / 2.0D;
        double originY = Math.max(12.0D, (IMAGE_SIZE - projectedHeight) / 2.0D);

        List<ProjectedVoxel> projected = scene.voxels().stream().map(voxel -> project(voxel, scene, rotation))
                .sorted(Comparator.comparingInt(ProjectedVoxel::depth).thenComparingInt(ProjectedVoxel::y))
                .toList();
        for (ProjectedVoxel voxel : projected) {
            double x = originX + (voxel.u() - voxel.v()) * tile * 0.5D;
            double y = originY + (voxel.u() + voxel.v()) * tile * 0.25D
                    + (scene.height() - 1 - voxel.y()) * tile * 0.5D;
            drawCube(graphics, x, y, tile, voxel.color());
        }
        graphics.dispose(); return image;
    }

    private static ProjectedVoxel project(Voxel voxel, Scene scene, int rotation) {
        return switch (rotation) {
            case 1 -> new ProjectedVoxel(voxel.z(), scene.width() - 1 - voxel.x(), voxel.y(), voxel.color());
            case 2 -> new ProjectedVoxel(scene.width() - 1 - voxel.x(), scene.depth() - 1 - voxel.z(), voxel.y(), voxel.color());
            case 3 -> new ProjectedVoxel(scene.depth() - 1 - voxel.z(), voxel.x(), voxel.y(), voxel.color());
            default -> new ProjectedVoxel(voxel.x(), voxel.z(), voxel.y(), voxel.color());
        };
    }

    private static void drawCube(Graphics2D graphics, double centerX, double topY, double tile, int rgb) {
        int half = Math.max(1, (int) Math.round(tile * 0.5D));
        int quarter = Math.max(1, (int) Math.round(tile * 0.25D));
        int height = Math.max(1, (int) Math.round(tile * 0.5D));
        int x = (int) Math.round(centerX), y = (int) Math.round(topY);
        Polygon top = new Polygon(new int[]{x, x + half, x, x - half},
                new int[]{y, y + quarter, y + quarter * 2, y + quarter}, 4);
        Polygon left = new Polygon(new int[]{x - half, x, x, x - half},
                new int[]{y + quarter, y + quarter * 2, y + quarter * 2 + height, y + quarter + height}, 4);
        Polygon right = new Polygon(new int[]{x + half, x, x, x + half},
                new int[]{y + quarter, y + quarter * 2, y + quarter * 2 + height, y + quarter + height}, 4);
        graphics.setColor(shade(rgb, 1.12D)); graphics.fillPolygon(top);
        graphics.setColor(shade(rgb, 0.72D)); graphics.fillPolygon(left);
        graphics.setColor(shade(rgb, 0.88D)); graphics.fillPolygon(right);
    }

    private static BufferedImage renderTop(Scene scene) {
        BufferedImage image = canvas(); Graphics2D graphics = image.createGraphics(); configure(graphics);
        Map<Long, Voxel> top = new LinkedHashMap<>();
        for (Voxel voxel : scene.voxels()) {
            long key = ((long) voxel.x() << 32) ^ (voxel.z() & 0xffffffffL);
            Voxel previous = top.get(key);
            if (previous == null || voxel.y() > previous.y()) top.put(key, voxel);
        }
        double cell = Math.max(0.35D, Math.min((IMAGE_SIZE - 24.0D) / Math.max(1, scene.width()),
                (IMAGE_SIZE - 24.0D) / Math.max(1, scene.depth())));
        double offsetX = (IMAGE_SIZE - scene.width() * cell) / 2.0D;
        double offsetY = (IMAGE_SIZE - scene.depth() * cell) / 2.0D;
        for (Voxel voxel : top.values()) {
            double heightShade = 0.72D + 0.35D * voxel.y() / Math.max(1.0D, scene.height() - 1.0D);
            graphics.setColor(shade(voxel.color(), heightShade));
            int x = (int) Math.floor(offsetX + voxel.x() * cell);
            int y = (int) Math.floor(offsetY + voxel.z() * cell);
            int size = Math.max(1, (int) Math.ceil(cell));
            graphics.fillRect(x, y, size, size);
        }
        graphics.dispose(); return image;
    }

    private static BufferedImage canvas() {
        BufferedImage image = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(236, 239, 242)); graphics.fillRect(0, 0, IMAGE_SIZE, IMAGE_SIZE);
        graphics.dispose(); return image;
    }

    private static void configure(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private static int color(ResourceLocation id) {
        String path = id.getPath();
        if (contains(path, "glass", "ice")) return 0x8CCDDD;
        if (contains(path, "quartz", "white_concrete", "calcite")) return 0xE6E3DC;
        if (contains(path, "black", "obsidian", "deepslate")) return 0x34333B;
        if (contains(path, "gray", "stone", "andesite", "tuff")) return 0x858585;
        if (contains(path, "brick", "terracotta")) return 0xA65343;
        if (contains(path, "copper")) return path.contains("oxidized") ? 0x4B9B83 : 0xC36D42;
        if (contains(path, "prismarine")) return 0x5AA69B;
        if (contains(path, "gold", "yellow")) return 0xE7BE39;
        if (contains(path, "iron", "light_gray")) return 0xC5C8C7;
        if (contains(path, "water", "blue")) return 0x3979C7;
        if (contains(path, "leaves", "grass", "moss", "green")) return 0x5D8F48;
        if (contains(path, "flower", "pink", "magenta", "purple")) return 0xB85FA2;
        if (contains(path, "wood", "log", "planks", "oak", "spruce", "birch", "jungle", "acacia", "cherry")) return 0x9B704A;
        int hash = id.toString().hashCode();
        return Color.HSBtoRGB(Math.floorMod(hash, 360) / 360.0F, 0.30F, 0.72F) & 0xFFFFFF;
    }

    private static boolean contains(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static Color shade(int rgb, double multiplier) {
        int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * multiplier));
        int g = Math.min(255, (int) (((rgb >> 8) & 0xFF) * multiplier));
        int b = Math.min(255, (int) ((rgb & 0xFF) * multiplier));
        return new Color(r, g, b);
    }

    record Scene(int width, int height, int depth, List<Voxel> voxels,
            Map<ResourceLocation, Integer> materials) {}
    record Voxel(int x, int y, int z, int color) {}
    private record ProjectedVoxel(int u, int v, int y, int color) {
        int depth() { return u + v; }
    }
}
