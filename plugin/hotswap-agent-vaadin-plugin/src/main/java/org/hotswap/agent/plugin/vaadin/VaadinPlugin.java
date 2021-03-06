package org.hotswap.agent.plugin.vaadin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import org.hotswap.agent.annotation.FileEvent;
import org.hotswap.agent.annotation.Init;
import org.hotswap.agent.annotation.LoadEvent;
import org.hotswap.agent.annotation.OnClassFileEvent;
import org.hotswap.agent.annotation.OnClassLoadEvent;
import org.hotswap.agent.annotation.Plugin;
import org.hotswap.agent.command.ReflectionCommand;
import org.hotswap.agent.command.Scheduler;
import org.hotswap.agent.javassist.CannotCompileException;
import org.hotswap.agent.javassist.CtClass;
import org.hotswap.agent.javassist.NotFoundException;
import org.hotswap.agent.logging.AgentLogger;
import org.hotswap.agent.util.PluginManagerInvoker;

/**
 * Vaadin 14.0+ plugin for HotswapAgent.
 *
 * https://vaadin.com
 *
 * @author Artur Signell
 * @author Matti Tahvonen
 * @autho Johannes Eriksson
 */
@Plugin(name = "Vaadin",
        description = "Vaadin Platform support",
        testedVersions = {"14.1.20", "14.2.0.alpha7", "15.0.2"},
        expectedVersions = {"14.1.20", "15.0.2"})
public class VaadinPlugin {

    @Init
    Scheduler scheduler;

    @Init
    ClassLoader appClassLoader;

    private UpdateRoutesCommand updateRouteRegistryCommand;

    private ReflectionCommand reloadCommand;

    private ReflectionCommand clearReflectionCache = new ReflectionCommand(this,
            "com.vaadin.flow.internal.ReflectionCache", "clearAll");

    private Set<Class<?>> addedClasses = new HashSet<>();

    private Set<Class<?>> modifiedClasses = new HashSet<>();

    private static final AgentLogger LOGGER = AgentLogger.getLogger(VaadinPlugin.class);

    private static final int RELOAD_QUIET_TIME =1750; // ms

    public VaadinPlugin() {
    }

    @OnClassLoadEvent(classNameRegexp = "com.vaadin.flow.server.VaadinServlet")
    public static void init(CtClass ctClass)
            throws NotFoundException, CannotCompileException {
        String src = PluginManagerInvoker
                .buildInitializePlugin(VaadinPlugin.class);
        src += PluginManagerInvoker.buildCallPluginMethod(VaadinPlugin.class,
                "registerServlet", "this", Object.class.getName());
        ctClass.getDeclaredConstructor(new CtClass[0]).insertAfter(src);

        LOGGER.info("Initialized Vaadin plugin");
    }

    public void registerServlet(Object vaadinServlet) {
        try {
            Class<?> vaadinIntegrationClass = appClassLoader.loadClass(
                    VaadinIntegration.class.getName());
            Object vaadinIntegration = vaadinIntegrationClass.getConstructor()
                    .newInstance();
            scheduler.scheduleCommand(new ReflectionCommand(vaadinIntegration,
                    "servletInitialized", vaadinServlet));
            updateRouteRegistryCommand = new UpdateRoutesCommand(vaadinIntegration);
            reloadCommand = new ReflectionCommand(vaadinIntegration, "reload");
        } catch (ClassNotFoundException | NoSuchMethodException
                | InstantiationException | IllegalAccessException
                | InvocationTargetException ex) {
            LOGGER.error(null, ex);
        }
    }

    @OnClassLoadEvent(classNameRegexp = ".*", events = LoadEvent.REDEFINE)
    public void invalidateReflectionCache(CtClass ctClass) throws Exception {
        LOGGER.debug("Redefined class {}, clearing Vaadin reflection cache and reloading browser", ctClass.getName());
        scheduler.scheduleCommand(clearReflectionCache);
        scheduler.scheduleCommand(reloadCommand, RELOAD_QUIET_TIME);
    }

    @OnClassFileEvent(classNameRegexp = ".*", events = { FileEvent.CREATE, FileEvent.MODIFY })
    public void classCreated(FileEvent eventType, CtClass ctClass) throws Exception {
        if (FileEvent.CREATE.equals(eventType)) {
            LOGGER.debug("Create class file event for " + ctClass.getName());
            addedClasses.add(resolveClass(ctClass.getName()));
        } else if (FileEvent.MODIFY.equals(eventType)) {
            LOGGER.debug("Modify class file event for " + ctClass.getName());
            modifiedClasses.add(resolveClass(ctClass.getName()));
        }
        // Note that scheduling multiple calls to the same command postpones it
        scheduler.scheduleCommand(updateRouteRegistryCommand);
        scheduler.scheduleCommand(reloadCommand, RELOAD_QUIET_TIME);
    }

    private Class<?> resolveClass(String name) throws ClassNotFoundException {
        return Class.forName(name, true, appClassLoader);
    }

    private class UpdateRoutesCommand extends ReflectionCommand {
        private Object flowIntegration;

        UpdateRoutesCommand(Object vaadinIntegration) {
            super(vaadinIntegration, "updateRoutes", addedClasses, modifiedClasses);
            this.flowIntegration = vaadinIntegration;
        }

        // NOTE: Identity equality semantics

        @Override
        public boolean equals(Object that) {
            return this == that;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(flowIntegration);
        }

        @Override
        public void executeCommand() {
            super.executeCommand();
            addedClasses.clear();
            modifiedClasses.clear();
        }
    }
}
