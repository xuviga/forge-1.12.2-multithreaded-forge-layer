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
 * Declares which threads are allowed to invoke an event handler.
 *
 * MAIN   - owning world thread (client thread, server thread, or a whole-dimension tick thread). This is the
 *          default and preserves 1.12.2 behaviour.
 * REGION - a sub-dimension region worker only (Phase 3). A handler marked REGION is not called while a world is
 *          ticked by a whole-dimension thread.
 * ANY    - any thread; the handler must be written to be thread-safe.
 */
public enum ThreadType
{
    MAIN,
    REGION,
    ANY;

    public boolean acceptsCurrentThread()
    {
        switch (this)
        {
            case ANY:
                return true;
            case REGION:
                return TickContext.isRegionThread();
            case MAIN:
            default:
                return !TickContext.isRegionThread();
        }
    }
}
