/*
 * Minecraft Forge
 * Copyright (c) 2016-2020.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 */

package net.minecraftforge.common.threading;

/**
 * A square group of chunks owned by exactly one tick thread at a time.
 *
 * Region ids are stable for a world tick and are ordered; the deterministic commit order is (regionId, sequence).
 * Phase 0/1/2 only use whole-dimension ownership, where {@link #getRegion()} is null; this type is the foundation
 * for Phase 3 regionized ticking.
 */
public final class Region
{
    /** Default region edge in chunks (16 chunks = 256 blocks). */
    public static final int DEFAULT_SIZE_CHUNKS = 16;

    private final int id;
    private final int dimension;
    private final int minChunkX;
    private final int minChunkZ;
    private final int maxChunkX;
    private final int maxChunkZ;

    public Region(int id, int dimension, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ)
    {
        this.id = id;
        this.dimension = dimension;
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.maxChunkX = maxChunkX;
        this.maxChunkZ = maxChunkZ;
    }

    public static Region wholeDimension(int dimension)
    {
        return new Region(0, dimension, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    public static Region forChunk(int id, int dimension, int chunkX, int chunkZ, int sizeChunks)
    {
        if (sizeChunks <= 0)
        {
            throw new IllegalArgumentException("sizeChunks must be positive");
        }
        int minX = Math.floorDiv(chunkX, sizeChunks) * sizeChunks;
        int minZ = Math.floorDiv(chunkZ, sizeChunks) * sizeChunks;
        return new Region(id, dimension, minX, minZ, minX + sizeChunks - 1, minZ + sizeChunks - 1);
    }

    public int getId()
    {
        return id;
    }

    public int getDimension()
    {
        return dimension;
    }

    public int getMinChunkX()
    {
        return minChunkX;
    }

    public int getMinChunkZ()
    {
        return minChunkZ;
    }

    public int getMaxChunkX()
    {
        return maxChunkX;
    }

    public int getMaxChunkZ()
    {
        return maxChunkZ;
    }

    public boolean containsChunk(int chunkX, int chunkZ)
    {
        return chunkX >= minChunkX && chunkX <= maxChunkX && chunkZ >= minChunkZ && chunkZ <= maxChunkZ;
    }

    @Override
    public boolean equals(Object other)
    {
        if (this == other)
        {
            return true;
        }
        if (!(other instanceof Region))
        {
            return false;
        }
        Region region = (Region)other;
        return id == region.id && dimension == region.dimension && minChunkX == region.minChunkX &&
                minChunkZ == region.minChunkZ && maxChunkX == region.maxChunkX && maxChunkZ == region.maxChunkZ;
    }

    @Override
    public int hashCode()
    {
        int result = id;
        result = 31 * result + dimension;
        result = 31 * result + minChunkX;
        result = 31 * result + minChunkZ;
        result = 31 * result + maxChunkX;
        result = 31 * result + maxChunkZ;
        return result;
    }

    @Override
    public String toString()
    {
        return "Region{id=" + id + ", dim=" + dimension + ", chunks=[" + minChunkX + "," + minChunkZ + "]..[" +
                maxChunkX + "," + maxChunkZ + "]}";
    }
}
