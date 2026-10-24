package fr.geeklegend.slimeworld.format;

import com.github.luben.zstd.Zstd;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WorldCodec {
    private static final int MAGIC = 0x49534C44; // ISLD
    public static final int VERSION = 4;
    private static final int MAX_RAW_SIZE = 256 * 1024 * 1024;

    private WorldCodec() {
    }

    public static byte[] encode(WorldData worldData, int compressionlevel) throws IOException {
        ByteArrayOutputStream rawBytes = new ByteArrayOutputStream(64 * 1024);
        try (DataOutputStream out = new DataOutputStream(rawBytes)) {
            writeBody(out, worldData);
        }
        byte[] raw = rawBytes.toByteArray();
        byte[] compressed = Zstd.compress(raw, compressionlevel);

        ByteArrayOutputStream file = new ByteArrayOutputStream(compressed.length + 16);
        try (DataOutputStream out = new DataOutputStream(file)) {
            out.writeInt(MAGIC);
            out.writeShort(VERSION);
            out.writeInt(raw.length);
            out.write(compressed);
        }

        return file.toByteArray();
    }

    public static WorldData decode(byte[] file) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(file));
        if (in.readInt() != MAGIC) throw new IOException("This is not a world file (invalid magic)");
        int version = in.readUnsignedShort();
        if (version > VERSION) throw new IOException("Format version " + version + " not supported");
        int rawSize = in.readInt();
        if (rawSize < 0 || rawSize > MAX_RAW_SIZE) throw new IOException("Decompressed size invalid: " + rawSize);

        byte[] raw = Zstd.decompress(in.readAllBytes(), rawSize);
        try (DataInputStream body = new DataInputStream(new ByteArrayInputStream(raw))) {
            return readBody(body, version);
        }
    }

    private static void writeBody(DataOutputStream out, WorldData worldData) throws IOException {
        out.writeInt(worldData.radius());
        out.writeDouble(worldData.borderSize());
        WorldData.Spawn spawn = worldData.spawn();
        out.writeDouble(spawn.x());
        out.writeDouble(spawn.y());
        out.writeDouble(spawn.z());
        out.writeFloat(spawn.yaw());
        out.writeFloat(spawn.pitch());
        out.writeUTF(worldData.defaultBiome());

        out.writeInt(worldData.chunks().size());
        for (WorldData.ChunkData chunk : worldData.chunks().values()) {
            out.writeInt(chunk.x());
            out.writeInt(chunk.z());

            out.writeShort(chunk.sections().size());
            for (WorldData.SectionData section : chunk.sections()) {
                out.writeShort(section.sectionY());
                writeStrings(out, section.blockPalette());
                writeLongs(out, section.blocks());
                writeStrings(out, section.biomePalette());
                writeLongs(out, section.biomes());
            }

            out.writeInt(chunk.tiles().size());
            for (WorldData.TileData tile : chunk.tiles()) {
                out.writeInt(tile.x());
                out.writeInt(tile.y());
                out.writeInt(tile.z());
                out.writeShort(tile.items().length);
                for (byte[] item : tile.items()) writeBytes(out, item);
                out.writeBoolean(tile.data() != null);
                if (tile.data() != null) writeBytes(out, tile.data().getBytes(StandardCharsets.UTF_8));
            }

            out.writeInt(chunk.entities().size());
            for (WorldData.EntityData entity : chunk.entities()) {
                out.writeDouble(entity.x());
                out.writeDouble(entity.y());
                out.writeDouble(entity.z());
                out.writeFloat(entity.yaw());
                out.writeFloat(entity.pitch());
                writeBytes(out, entity.nbt());
            }
        }
    }

    private static WorldData readBody(DataInputStream in, int version) throws IOException {
        int radius = in.readInt();
        double borderSize = version >= 2 ? in.readDouble() : (2 * radius + 1) * 16;
        WorldData.Spawn spawn = new WorldData.Spawn(in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat(), in.readFloat());
        String defaultBiome = in.readUTF();

        int chunkCount = in.readInt();
        Map<Long, WorldData.ChunkData> chunks = new HashMap<>(Math.max(16, chunkCount * 2));
        for (int count = 0; count < chunkCount; count++) {
            int x = in.readInt();
            int z = in.readInt();

            int sectionCount = in.readUnsignedShort();
            List<WorldData.SectionData> sections = new ArrayList<>(sectionCount);
            for (int section = 0; section < sectionCount; section++) {
                sections.add(new WorldData.SectionData(in.readShort(), readStrings(in), readLongs(in), readStrings(in), readLongs(in)));
            }

            int tileCount = in.readInt();
            List<WorldData.TileData> tiles = new ArrayList<>(tileCount);
            for (int tile = 0; tile < tileCount; tile++) {
                int tileX = in.readInt(), tileY = in.readInt(), tileZ = in.readInt();
                byte[][] items = new byte[in.readUnsignedShort()][];
                for (int i = 0; i < items.length; i++) items[i] = readBytes(in);
                String data = null;
                if (version >= 4) {
                    if (in.readBoolean()) data = new String(readBytes(in), StandardCharsets.UTF_8);
                } else if (version == 3 && in.readBoolean()) {
                    data = readLegacySign(in);
                }
                tiles.add(new WorldData.TileData(tileX, tileY, tileZ, items, data));
            }

            int entityCount = in.readInt();
            List<WorldData.EntityData> entities = new ArrayList<>(entityCount);
            for (int entity = 0; entity < entityCount; entity++) {
                entities.add(new WorldData.EntityData(in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat(), in.readFloat(), readBytes(in)));
            }

            chunks.put(WorldData.chunkKey(x, z), new WorldData.ChunkData(x, z, sections, tiles, entities));
        }

        return new WorldData(radius, borderSize, spawn, defaultBiome, chunks);
    }

    /**
     * Version 3 stored only signs, converted to the TileDataAccess JSON.
     */
    private static String readLegacySign(DataInputStream in) throws IOException {
        JsonObject sign = new JsonObject();
        sign.add("front", readLegacySignSide(in));
        sign.add("back", readLegacySignSide(in));
        sign.addProperty("waxed", in.readBoolean());
        JsonObject json = new JsonObject();
        json.add("sign", sign);
        return json.toString();
    }

    private static JsonObject readLegacySignSide(DataInputStream in) throws IOException {
        JsonArray lines = new JsonArray();
        for (String line : readStrings(in)) lines.add(line);
        JsonObject side = new JsonObject();
        side.add("lines", lines);
        side.addProperty("color", in.readUTF());
        side.addProperty("glowing", in.readBoolean());
        return side;
    }

    private static void writeStrings(DataOutputStream out, String[] values) throws IOException {
        out.writeShort(values.length);
        for (String value : values) out.writeUTF(value);
    }

    private static String[] readStrings(DataInputStream in) throws IOException {
        String[] values = new String[in.readUnsignedShort()];
        for (int i = 0; i < values.length; i++) values[i] = in.readUTF();
        return values;
    }

    private static void writeLongs(DataOutputStream out, long[] values) throws IOException {
        out.writeShort(values.length);
        for (long value : values) out.writeLong(value);
    }

    private static long[] readLongs(DataInputStream in) throws IOException {
        long[] values = new long[in.readUnsignedShort()];
        for (int i = 0; i < values.length; i++) values[i] = in.readLong();
        return values;
    }

    private static void writeBytes(DataOutputStream out, byte[] data) throws IOException {
        out.writeInt(data.length);
        out.write(data);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_RAW_SIZE) throw new IOException("Invalid length: " + length);
        byte[] data = in.readNBytes(length);
        if (data.length != length) throw new IOException("File truncated");
        return data;
    }
}
