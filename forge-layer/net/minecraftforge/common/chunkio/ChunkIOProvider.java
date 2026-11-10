/*
 * Minecraft Forge
 * Copyright (c) 2016-2020.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.common.chunkio;

import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.ChunkPos;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.ChunkDataEvent;

import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;

class ChunkIOProvider implements Runnable
{
    private final QueuedChunk chunkInfo;
    private final AnvilChunkLoader loader;
    private final ChunkProviderServer provider;

    /** Written by the owning game thread in syncCallback(). */
    private volatile Chunk chunk;
    /** Written by the async I/O worker, read by the owning game thread in syncCallback(). */
    private volatile NBTTagCompound nbt;
    private final ConcurrentLinkedQueue<Runnable> callbacks = new ConcurrentLinkedQueue<Runnable>();
    private volatile boolean ran = false;

    ChunkIOProvider(QueuedChunk chunk, AnvilChunkLoader loader, ChunkProviderServer provider)
    {
        this.chunkInfo = chunk;
        this.loader = loader;
        this.provider = provider;
    }

    public void addCallback(Runnable callback)
    {
        this.callbacks.add(callback);
    }
    public void removeCallback(Runnable callback)
    {
        this.callbacks.remove(callback);
    }

    @Override
    public void run() // async stuff
    {
        synchronized(this)
        {
            try
            {
                try
                {
                    // Only read NBT here. Chunk construction touches the World (capabilities, provider),
                    // so it is deferred to syncCallback() on the owning game thread.
                    this.nbt = this.loader.loadChunkNBT(chunkInfo.world, chunkInfo.x, chunkInfo.z);
                }
                catch (IOException e)
                {
                    throw new RuntimeException(e); // Allow exception to bubble up to afterExecute
                }
            }
            finally 
            {
                this.ran = true;
                this.notifyAll();
            }
        }
    }

    // sync stuff
    public void syncCallback()
    {
        this.syncCallback(true);
    }

    /** Synchronous loader path: populate inline so the caller never observes an unpopulated chunk. */
    public void syncCallbackNow()
    {
        this.syncCallback(false);
    }

    private void syncCallback(boolean deferPost)
    {
        if (this.nbt == null)
        {
            this.runCallbacks();
            return;
        }

        // Build the Chunk on the owning game thread (the constructor fires AttachCapabilitiesEvent).
        if (this.chunk == null)
        {
            Object[] data = this.loader.buildChunkFromNBT(this.chunkInfo.world, this.chunkInfo.x, this.chunkInfo.z, this.nbt);

            if (data == null)
            {
                this.runCallbacks();
                return;
            }

            this.chunk = (Chunk)data[0];
            this.nbt   = (NBTTagCompound)data[1];
        }

        // Load Entities
        this.loader.loadEntities(this.chunkInfo.world, this.nbt.getCompoundTag("Level"), this.chunk);

        MinecraftForge.EVENT_BUS.post(new ChunkDataEvent.Load(this.chunk, this.nbt)); // Don't call ChunkDataEvent.Load async

        this.chunk.setLastSaveTime(provider.world.getTotalWorldTime());

        provider.loadedChunks.put(ChunkPos.asLong(this.chunkInfo.x, this.chunkInfo.z), this.chunk);
        this.chunk.onLoad();

        if (deferPost)
        {
            // recreateStructures/populate touch shared generator and structure state. Run them through the bounded
            // pump so they can never race the background generator, and so the server thread never blocks on it.
            this.provider.queueChunkPostProcess(this.chunk, true, this::runCallbacks);
        }
        else
        {
            // Synchronous callers need a fully populated chunk before this method returns.
            net.minecraftforge.common.threading.ChunkGenExecutor.lockWorldGen();
            try
            {
                this.provider.postProcessChunk(this.chunk, true);
            }
            finally
            {
                net.minecraftforge.common.threading.ChunkGenExecutor.unlockWorldGen();
            }
            this.runCallbacks();
        }
    }

    public Chunk getChunk()
    {
        return this.chunk;
    }

    public boolean runFinished()
    {
        return this.ran;
    }

    public boolean hasCallback()
    {
        return this.callbacks.size() > 0;
    }

    public void runCallbacks()
    {
        for (Runnable r : this.callbacks)
        {
            r.run();
        }

        this.callbacks.clear();
    }

    public QueuedChunk getChunkInfo() 
    {
    	return chunkInfo;
    }
}
