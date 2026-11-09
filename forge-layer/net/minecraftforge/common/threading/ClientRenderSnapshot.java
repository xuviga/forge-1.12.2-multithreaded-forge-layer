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

import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;

/**
 * Immutable snapshot of the client entity list and render-relevant position/rotation fields.
 *
 * The simulation thread publishes a fresh snapshot into a volatile slot after each entity tick; the render thread
 * can read it without touching the live entity lists. Renderers still use the Entity objects themselves, so this is
 * the first half of the double-buffered render path (B2b); full lock-free rendering needs renderers to consume the
 * snapshot fields instead of live entity fields.
 */
public final class ClientRenderSnapshot
{
    private final Entity[] entities;
    private final double[] posX;
    private final double[] posY;
    private final double[] posZ;
    private final float[] yaw;
    private final float[] pitch;

    private ClientRenderSnapshot(List<Entity> source)
    {
        int size = source.size();
        this.entities = new Entity[size];
        this.posX = new double[size];
        this.posY = new double[size];
        this.posZ = new double[size];
        this.yaw = new float[size];
        this.pitch = new float[size];

        for (int index = 0; index < size; index++)
        {
            Entity entity = source.get(index);
            this.entities[index] = entity;
            this.posX[index] = entity.posX;
            this.posY[index] = entity.posY;
            this.posZ[index] = entity.posZ;
            this.yaw[index] = entity.rotationYaw;
            this.pitch[index] = entity.rotationPitch;
        }
    }

    public static ClientRenderSnapshot capture(World world)
    {
        if (world == null)
        {
            return null;
        }
        return new ClientRenderSnapshot(new ArrayList<Entity>(world.loadedEntityList));
    }

    public int size()
    {
        return this.entities.length;
    }

    public Entity entity(int index)
    {
        return this.entities[index];
    }

    public double posX(int index)
    {
        return this.posX[index];
    }

    public double posY(int index)
    {
        return this.posY[index];
    }

    public double posZ(int index)
    {
        return this.posZ[index];
    }

    public float yaw(int index)
    {
        return this.yaw[index];
    }

    public float pitch(int index)
    {
        return this.pitch[index];
    }
}
