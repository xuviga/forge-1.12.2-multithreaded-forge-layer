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
 * Ordered phases of a world/region tick. The order is part of the determinism contract: every region executes the
 * same phases, and cross-region commits are only applied at the BARRIER by the owning main thread.
 */
public enum TickPhase
{
    INPUT,
    WORLD,
    ENTITIES,
    BLOCK_ENTITIES,
    TRACKING,
    OUTPUT,
    BARRIER
}
