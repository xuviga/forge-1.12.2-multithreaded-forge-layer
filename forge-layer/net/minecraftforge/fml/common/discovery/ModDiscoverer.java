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

package net.minecraftforge.fml.common.discovery;

import java.io.File;
import java.util.List;

import net.minecraftforge.common.threading.LoadingExecutor;
import net.minecraftforge.common.threading.Threading;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.LoaderException;
import net.minecraftforge.fml.common.ModClassLoader;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.relauncher.CoreModManager;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

public class ModDiscoverer
{
    private List<ModCandidate> candidates = Lists.newArrayList();

    private ASMDataTable dataTable = new ASMDataTable();

    private List<File> nonModLibs = Lists.newArrayList();

    public void findClasspathMods(ModClassLoader modClassLoader)
    {
        List<String> knownLibraries = ImmutableList.<String>builder()
                // skip default libs
                .addAll(modClassLoader.getDefaultLibraries())
                // skip loaded coremods
                .addAll(CoreModManager.getIgnoredMods())
                // skip reparse coremods here
                .addAll(CoreModManager.getReparseableCoremods())
                .build();
        File[] minecraftSources = modClassLoader.getParentSources();
        if (minecraftSources.length == 1 && minecraftSources[0].isFile())
        {
            FMLLog.log.debug("Minecraft is a file at {}, loading", minecraftSources[0].getAbsolutePath());
            addCandidate(new ModCandidate(minecraftSources[0], minecraftSources[0], ContainerType.JAR, true, true));
        }
        else
        {
            int i = 0;
            for (File source : minecraftSources)
            {
                if (source.isFile())
                {
                    if (knownLibraries.contains(source.getName()) || modClassLoader.isDefaultLibrary(source))
                    {
                        FMLLog.log.trace("Skipping known library file {}", source.getAbsolutePath());
                    }
                    else
                    {
                        FMLLog.log.debug("Found a minecraft related file at {}, examining for mod candidates", source.getAbsolutePath());
                        addCandidate(new ModCandidate(source, source, ContainerType.JAR, i==0, true));
                    }
                }
                else if (minecraftSources[i].isDirectory())
                {
                    FMLLog.log.debug("Found a minecraft related directory at {}, examining for mod candidates", source.getAbsolutePath());
                    addCandidate(new ModCandidate(source, source, ContainerType.DIR, i==0, true));
                }
                i++;
            }
        }

    }

    public List<ModContainer> identifyMods()
    {
        List<ModContainer> modList = Lists.newArrayList();
        final int size = candidates.size();
        if (size == 0)
        {
            return modList;
        }

        // Each candidate is scanned into its own ASMDataTable so no two workers ever touch the same multimap.
        // We merge in the original candidate order afterwards, which keeps duplicate detection, package ownership
        // and the eventual mod list byte-for-byte identical to the sequential path.
        final CandidateOutcome[] outcomes = new CandidateOutcome[size];
        boolean parallel = Threading.isParallelModDiscoveryEnabled() && size > 1;
        if (parallel)
        {
            long start = System.nanoTime();
            LoadingExecutor.runIndexed(size, "load.discovery", index ->
                    outcomes[index] = exploreCandidate(candidates.get(index)));
            FMLLog.log.info("Scanned {} mod candidates in {} ms using the load pool", Integer.valueOf(size),
                    Long.valueOf((System.nanoTime() - start) / 1_000_000L));
        }
        else
        {
            for (int index = 0; index < size; index++)
            {
                outcomes[index] = exploreCandidate(candidates.get(index));
            }
        }

        for (CandidateOutcome outcome : outcomes)
        {
            dataTable.merge(outcome.table);
            if (outcome.mods.isEmpty() && !outcome.candidate.isClasspath())
            {
                nonModLibs.add(outcome.candidate.getModContainer());
            }
            else
            {
                modList.addAll(outcome.mods);
            }
        }

        return modList;
    }

    private CandidateOutcome exploreCandidate(ModCandidate candidate)
    {
        ASMDataTable localTable = new ASMDataTable();
        List<ModContainer> mods = Lists.newArrayList();
        try
        {
            mods.addAll(candidate.explore(localTable));
        }
        catch (LoaderException le)
        {
            FMLLog.log.warn("Identified a problem with the mod candidate {}, ignoring this source", candidate.getModContainer(), le);
        }
        return new CandidateOutcome(candidate, mods, localTable);
    }

    private static final class CandidateOutcome
    {
        private final ModCandidate candidate;
        private final List<ModContainer> mods;
        private final ASMDataTable table;

        private CandidateOutcome(ModCandidate candidate, List<ModContainer> mods, ASMDataTable table)
        {
            this.candidate = candidate;
            this.mods = mods;
            this.table = table;
        }
    }

    public ASMDataTable getASMTable()
    {
        return dataTable;
    }

    public List<File> getNonModLibs()
    {
        return nonModLibs;
    }

    public void addCandidate(ModCandidate candidate)
    {
        for (ModCandidate c : candidates)
        {
            if (c.getModContainer().equals(candidate.getModContainer()))
            {
                FMLLog.log.trace("  Skipping already in list {}", candidate.getModContainer());
                return;
            }
        }
        candidates.add(candidate);
    }
}
