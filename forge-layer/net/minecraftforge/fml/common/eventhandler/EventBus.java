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

package net.minecraftforge.fml.common.eventhandler;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nonnull;

import net.minecraftforge.common.threading.ITickThread;
import net.minecraftforge.common.threading.MainThreadDispatcher;
import net.minecraftforge.common.threading.ThreadAudit;
import net.minecraftforge.common.threading.ThreadType;
import net.minecraftforge.common.threading.TickContext;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.MapMaker;
import com.google.common.collect.Sets;
import com.google.common.reflect.TypeToken;

public class EventBus implements IEventExceptionHandler
{
    private static final AtomicInteger maxID = new AtomicInteger(0);

    private ConcurrentHashMap<Object, ArrayList<IEventListener>> listeners = new ConcurrentHashMap<Object, ArrayList<IEventListener>>();
    private Map<Object,ModContainer> listenerOwners = new MapMaker().weakKeys().weakValues().makeMap();
    private final int busID = maxID.getAndIncrement();
    private volatile IEventExceptionHandler exceptionHandler;
    private volatile boolean shutdown;

    public EventBus()
    {
        ListenerList.resize(busID + 1);
        exceptionHandler = this;
    }

    public EventBus(@Nonnull IEventExceptionHandler handler)
    {
        this();
        Preconditions.checkNotNull(handler, "EventBus exception handler can not be null");
        exceptionHandler = handler;
    }

    public void register(Object target)
    {
        if (listeners.containsKey(target))
        {
            return;
        }

        ModContainer activeModContainer = Loader.instance().activeModContainer();
        if (activeModContainer == null)
        {
            FMLLog.log.error("Unable to determine registrant mod for {}. This is a critical error and should be impossible", target, new Throwable());
            activeModContainer = Loader.instance().getMinecraftModContainer();
        }
        listenerOwners.put(target, activeModContainer);
        boolean isStatic = target.getClass() == Class.class;
        @SuppressWarnings("unchecked")
        Set<? extends Class<?>> supers = isStatic ? Sets.newHashSet((Class<?>)target) : TypeToken.of(target.getClass()).getTypes().rawTypes();
        for (Method method : (isStatic ? (Class<?>)target : target.getClass()).getMethods())
        {
            if (isStatic && !Modifier.isStatic(method.getModifiers()))
                continue;
            else if (!isStatic && Modifier.isStatic(method.getModifiers()))
                continue;

            for (Class<?> cls : supers)
            {
                try
                {
                    Method real = cls.getDeclaredMethod(method.getName(), method.getParameterTypes());
                    if (real.isAnnotationPresent(SubscribeEvent.class))
                    {
                        Class<?>[] parameterTypes = method.getParameterTypes();
                        if (parameterTypes.length != 1)
                        {
                            throw new IllegalArgumentException(
                                "Method " + method + " has @SubscribeEvent annotation, but requires " + parameterTypes.length +
                                " arguments.  Event handler methods must require a single argument."
                            );
                        }

                        Class<?> eventType = parameterTypes[0];

                        if (!Event.class.isAssignableFrom(eventType))
                        {
                            throw new IllegalArgumentException("Method " + method + " has @SubscribeEvent annotation, but takes a argument that is not an Event " + eventType);
                        }

                        register(eventType, target, real, activeModContainer);
                        break;
                    }
                }
                catch (NoSuchMethodException e)
                {
                    ; // Eat the error, this is not unexpected
                }
            }
        }
    }

    private void register(Class<?> eventType, Object target, Method method, final ModContainer owner)
    {
        try
        {
            Constructor<?> ctr = eventType.getConstructor();
            ctr.setAccessible(true);
            Event event = (Event)ctr.newInstance();
            final ASMEventHandler asm = new ASMEventHandler(target, method, owner, IGenericEvent.class.isAssignableFrom(eventType));

            IEventListener listener = asm;
            if (IContextSetter.class.isAssignableFrom(eventType))
            {
                listener = new IEventListener()
                {
                    @Override
                    public void invoke(Event event)
                    {
                        ModContainer old = Loader.instance().activeModContainer();
                        Loader.instance().setActiveModContainer(owner);
                        ((IContextSetter)event).setModContainer(owner);
                        asm.invoke(event);
                        Loader.instance().setActiveModContainer(old);
                    }
                };
            }

            IEventListener bound = new ThreadBoundListener(listener, asm.getThreadType());
            event.getListenerList().register(busID, asm.getPriority(), bound);

            ArrayList<IEventListener> others = listeners.computeIfAbsent(target, k -> new ArrayList<>());
            synchronized (others)
            {
                others.add(bound);
            }
        }
        catch (Exception e)
        {
            FMLLog.log.error("Error registering event handler: {} {} {}", owner, eventType, method, e);
        }
    }

    public void unregister(Object object)
    {
        ArrayList<IEventListener> list = listeners.remove(object);
        if(list == null)
            return;
        for (IEventListener listener : list)
        {
            ListenerList.unregisterAll(busID, listener);
        }
    }

    public boolean post(Event event)
    {
        if (shutdown) return false;

        IEventListener[] listeners = event.getListenerList().getListeners(busID);
        int index = 0;
        try
        {
            for (; index < listeners.length; index++)
            {
                listeners[index].invoke(event);
            }
        }
        catch (Throwable throwable)
        {
            exceptionHandler.handleException(this, event, listeners, index, throwable);
            Throwables.throwIfUnchecked(throwable);
            throw new RuntimeException(throwable);
        }
        return event.isCancelable() && event.isCanceled();
    }

    /**
     * Posts an event with an explicit owner-thread context. Region workers call this so MAIN listeners are skipped
     * and REGION/ANY listeners see the correct TickContext.
     */
    public boolean post(Event event, ITickThread thread)
    {
        if (thread == null)
        {
            return post(event);
        }

        TickContext current = TickContext.current();
        if (current != null && current.getWorld() == thread.getWorld() &&
                current.getRegion() == thread.getRegion() && current.getPhase() == thread.getPhase())
        {
            return post(event);
        }

        TickContext context = thread.newContext();
        TickContext.push(context);
        try
        {
            return post(event);
        }
        finally
        {
            TickContext.pop();
        }
    }

    public void shutdown()
    {
        FMLLog.log.warn("EventBus {} shutting down - future events will not be posted.", busID);
        shutdown = true;
    }

    private static final class ThreadBoundListener implements IEventListener
    {
        private final IEventListener delegate;
        private final ThreadType threadType;

        private ThreadBoundListener(IEventListener delegate, ThreadType threadType)
        {
            this.delegate = delegate;
            this.threadType = threadType;
        }

        @Override
        public void invoke(final Event event)
        {
            if (threadType.acceptsCurrentThread())
            {
                delegate.invoke(event);
                return;
            }

            // Legacy adapter. MAIN handlers run inline on the region worker by default: forwarding every legacy
            // handler to the owner thread serializes all region workers and destroys heavy-modpack throughput.
            // Set -Dforge.threading.forwardMainEvents=true only for packs with handlers that are not thread-safe.
            if (threadType == ThreadType.MAIN && TickContext.isRegionThread() && MainThreadDispatcher.hasOwner())
            {
                if (Boolean.parseBoolean(System.getProperty("forge.threading.forwardMainEvents", "false")))
                {
                    MainThreadDispatcher.dispatch(new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            delegate.invoke(event);
                        }
                    });
                }
                else
                {
                    ThreadAudit.recordInlineMainHandler(delegate.toString());
                    delegate.invoke(event);
                }
                return;
            }

            ThreadAudit.recordSkippedListener(delegate.toString());
        }

        @Override
        public String toString()
        {
            return delegate.toString();
        }
    }

    @Override
    public void handleException(EventBus bus, Event event, IEventListener[] listeners, int index, Throwable throwable)
    {
        FMLLog.log.error("Exception caught during firing event {}:", event, throwable);
        FMLLog.log.error("Index: {} Listeners:", index);
        for (int x = 0; x < listeners.length; x++)
        {
            FMLLog.log.error("{}: {}", x, listeners[x]);
        }
    }
}
