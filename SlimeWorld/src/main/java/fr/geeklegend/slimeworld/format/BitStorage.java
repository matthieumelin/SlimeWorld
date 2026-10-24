package fr.geeklegend.slimeworld.format;

public class BitStorage {

    private BitStorage() {
    }

    public static int bitsFor(int paletteSize) {
        return paletteSize <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
    }

    public static long[] pack(int[] values, int bits) {
        if (bits == 0) return new long[0];
        int perLong = 64 / bits;
        long mask = (1L << bits) - 1;
        long[] out = new long[(values.length + perLong - 1) / perLong];
        for (int i = 0; i < values.length; i++) {
            out[i / perLong] |= (values[i] & mask) << ((i % perLong) * bits);
        }
        return out;
    }

    public static int get(long[] data, int bits, int index) {
        if (bits == 0) return 0;
        int perLong = 64 / bits;
        return (int) ((data[index / perLong] >>> ((index % perLong) * bits)) & ((1L << bits) - 1));
    }
}
