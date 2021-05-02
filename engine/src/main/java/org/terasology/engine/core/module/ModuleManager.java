// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.core.module;

import com.google.common.collect.Sets;
import org.reflections.Reflections;
import org.reflections.scanners.Scanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.engine.config.Config;
import org.terasology.engine.config.SystemConfig;
import org.terasology.engine.core.TerasologyConstants;
import org.terasology.engine.core.paths.PathManager;
import org.terasology.gestalt.module.Module;
import org.terasology.gestalt.module.ModuleEnvironment;
import org.terasology.gestalt.module.ModuleFactory;
import org.terasology.gestalt.module.ModuleMetadataJsonAdapter;
import org.terasology.gestalt.module.ModuleMetadataLoader;
import org.terasology.gestalt.module.ModulePathScanner;
import org.terasology.gestalt.module.ModuleRegistry;
import org.terasology.gestalt.module.TableModuleRegistry;
import org.terasology.gestalt.module.dependencyresolution.DependencyInfo;
import org.terasology.gestalt.module.dependencyresolution.DependencyResolver;
import org.terasology.gestalt.module.dependencyresolution.ResolutionResult;
import org.terasology.gestalt.module.sandbox.API;
import org.terasology.gestalt.module.sandbox.APIScanner;
import org.terasology.gestalt.module.sandbox.ModuleSecurityManager;
import org.terasology.gestalt.module.sandbox.ModuleSecurityPolicy;
import org.terasology.gestalt.module.sandbox.PermissionProvider;
import org.terasology.gestalt.module.sandbox.PermissionProviderFactory;
import org.terasology.gestalt.module.sandbox.StandardPermissionProviderFactory;
import org.terasology.gestalt.module.sandbox.WarnOnlyProviderFactory;
import org.terasology.gestalt.naming.Name;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.ReflectPermission;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.security.Policy;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class ModuleManager {
    /** Set this property to "true" to allow modules on the classpath. */
    public final static String LOAD_CLASSPATH_MODULES_PROPERTY = "org.terasology.load_classpath_modules";

    private static final Logger logger = LoggerFactory.getLogger(ModuleManager.class);
    private final StandardPermissionProviderFactory permissionProviderFactory = new StandardPermissionProviderFactory();
    private final PermissionProviderFactory wrappingPermissionProviderFactory =
            new WarnOnlyProviderFactory(permissionProviderFactory);

    private final ModuleRegistry registry;
    private ModuleEnvironment environment;
    private final ModuleMetadataJsonAdapter metadataReader;
    private final ModuleFactory moduleFactory;
    private final ModuleInstallManager installManager;
    private final Module engineModule;

    Supplier<PathManager> pathManagerFactory = PathManager::getInstance;

    public ModuleManager(String masterServerAddress) {
        this(masterServerAddress, Collections.emptyList());
    }

    public ModuleManager(String masterServerAddress, List<Class<?>> classesOnClasspathsToAddToEngine) {
        PathManager pathManager = pathManagerFactory.get();  // get early so if it needs to initialize, it does it now

        metadataReader = newMetadataReader();

        moduleFactory = newModuleFactory(metadataReader);

        registry = new TableModuleRegistry();

        engineModule = createAndRegisterEngineModule(classesOnClasspathsToAddToEngine);

        loadModulesFromApplicationPath(pathManager);

        ensureModulesDependOnEngine();

        setupSandbox();
        loadEnvironment(Sets.newHashSet(engineModule), true);
        installManager = new ModuleInstallManager(this, masterServerAddress);
    }

    static ModuleFactory newModuleFactory(ModuleMetadataJsonAdapter metadataReader) {
        final ModuleFactory moduleFactory;
        if (Boolean.getBoolean(LOAD_CLASSPATH_MODULES_PROPERTY)) {
            moduleFactory = new ClasspathCompromisingModuleFactory();
        } else {
            moduleFactory = new ModuleFactory();
        }
        moduleFactory.setDefaultLibsSubpath("build/libs");

        Map<String, ModuleMetadataLoader> mmlm = moduleFactory.getModuleMetadataLoaderMap();
        mmlm.put(TerasologyConstants.MODULE_INFO_FILENAME.toString(), metadataReader);
        mmlm.put("engine-module.txt", metadataReader);  // FIXME: this should be *only* for engine-module.
        // FIXME: …or maybe engine-module doesn't need a special filename anymore?
        return moduleFactory;
    }

    /**
     * I wondered why this is important, and found MovingBlocks/Terasology#1450.
     * It's not a worry that the engine module wouldn't be loaded without it.
     * It's about ordering: some things run in an order derived from the dependency
     * tree, and we want to make sure engine is at the root of it.
     */
    private void ensureModulesDependOnEngine() {
        DependencyInfo engineDep = new DependencyInfo();
        engineDep.setId(engineModule.getId());
        engineDep.setMinVersion(engineModule.getVersion());

        HashSet<Name> engineModules = Sets.newHashSet(engineModule.getId());
        engineModules.addAll(engineModule.getMetadata().getDependencies().stream().map(DependencyInfo::getId).collect(Collectors.toList()));

        registry.stream()
                .filter(mod -> !engineModules.contains(mod.getId()))
                .forEach(mod -> mod.getMetadata().getDependencies().add(engineDep));
    }

    private void loadModulesFromApplicationPath(PathManager pathManager) {
        ModulePathScanner scanner = new ModulePathScanner(moduleFactory);
        List<File> paths = pathManager.getModulePaths().stream().map(Path::toFile).collect(Collectors.toList());
         scanner.scan(registry, paths);
    }

    private Module createAndRegisterEngineModule(List<Class<?>> classesOnClasspathsToAddToEngine) {
        Module engine = loadAndConfigureEngineModule(moduleFactory, classesOnClasspathsToAddToEngine);
        registry.add(engine);
        return engine;
    }

    /**
     * Load and configure the engine module.
     * <p>
     * The engine module is the parts of the engine which are available to be called directly
     * from other modules. Unlike other modules, engine classes are on the classpath and not
     * restricted by the ModuleClassLoader.
     * <p>
     * This function is static so it can be tested without needing a ModuleManager instance.
     *
     * @param moduleFactory used to create the module
     * @param classesOnClasspathsToAddToEngine added to the module's reflections manifest
     */
    static Module loadAndConfigureEngineModule(ModuleFactory moduleFactory, List<Class<?>> classesOnClasspathsToAddToEngine) {
        Module packageModule = moduleFactory.createPackageModule("org.terasology.engine");
        // We need to add reflections from our subsystems and other classes.
        ConfigurationBuilder config = new ConfigurationBuilder();
        Reflections packageReflections = packageModule.getModuleManifest();
        Set<Scanner> scanners = packageReflections.getConfiguration().getScanners();
        config.setScanners(scanners.toArray(new Scanner[0]));

        Collection<File> classPaths = new HashSet<>(packageModule.getClasspaths());
        for (Class<?> aClass : classesOnClasspathsToAddToEngine) {
            URL url = ClasspathHelper.forClass(aClass);
            config.addUrls(url);
            classPaths.add(urlToFile(url));
        }

        if (!config.getUrls().isEmpty()) {
            Reflections reflectionsWithSubsystems = new Reflections(config);
            packageReflections.merge(reflectionsWithSubsystems);
        }

        // We need the class predicate to include classes in subsystems and whatnot. We can't change it in an
        // existing module, so make a new one based on the one from the moduleFactory.
        return new Module(
                packageModule.getMetadata(),
                packageModule.getResources(),
                classPaths,
                packageReflections,
                clazz ->
                        packageModule.getClassPredicate().test(clazz)
                                || config.getUrls().contains(ClasspathHelper.forClass(clazz))
        );
    }

    static boolean isInAnnotatedPackage(Class<?> clazz) {
        Package pkg = clazz.getPackage();
        if (pkg.isAnnotationPresent(API.class)) {
            return true;
        }
        String name = pkg.getName();
        int pos = name.lastIndexOf(".");
        while (pos > 0) {
            Package ancestor = Package.getPackage(name.substring(0, pos));
            if (ancestor == null) {
                return false;
            } else if (ancestor.isAnnotationPresent(API.class)) {
                return true;
            }
            pos = name.lastIndexOf(".", pos - 1);
        }
        return false;
    }

    public ModuleManager(Config config) {
        this(config, Collections.emptyList());
    }

    public ModuleManager(Config config, List<Class<?>> classesOnClasspathsToAddToEngine) {
        this(config.getNetwork().getMasterServer(), classesOnClasspathsToAddToEngine);
    }

    static ModuleMetadataJsonAdapter newMetadataReader() {
        final ModuleMetadataJsonAdapter metadataJsonAdapter = new ModuleMetadataJsonAdapter();
        for (ModuleExtension ext : StandardModuleExtension.values()) {
            metadataJsonAdapter.registerExtension(ext.getKey(), ext.getValueType());
        }
        for (ModuleExtension ext : ExtraDataModuleExtension.values()) {
            metadataJsonAdapter.registerExtension(ext.getKey(), ext.getValueType());
        }
        return metadataJsonAdapter;
    }

    private void setupSandbox() {
        ExternalApiWhitelist.CLASSES.stream().forEach(clazz ->
                permissionProviderFactory.getBasePermissionSet().addAPIClass(clazz));
        ExternalApiWhitelist.PACKAGES.stream().forEach(packagee ->
                permissionProviderFactory.getBasePermissionSet().addAPIPackage(packagee));

        APIScanner apiScanner = new APIScanner(permissionProviderFactory);
        registry.stream().map(Module::getModuleManifest).forEach(apiScanner::scan);

        permissionProviderFactory.getBasePermissionSet().grantPermission("com.google.gson", ReflectPermission.class);
        permissionProviderFactory.getBasePermissionSet().grantPermission("com.google.gson.internal", ReflectPermission.class);

        Policy.setPolicy(new ModuleSecurityPolicy());
        System.setSecurityManager(new ModuleSecurityManager());
    }

    /**
     * the registry this ModuleManager uses internally
     *
     * @deprecated Use {@link #resolveAndLoadEnvironment} if you need module dependency resolution.
     */
    @Deprecated(/*since="4.4.0"*/)
    public ModuleRegistry getRegistry() {
        return registry;
    }

    public ModuleInstallManager getInstallManager() {
        return installManager;
    }

    public ModuleEnvironment getEnvironment() {
        return environment;
    }

    /** Create and register a new module for this package. */
    public Module registerPackageModule(String packageName) {
        Module module = moduleFactory.createPackageModule(packageName);
        registry.add(module);
        ensureModulesDependOnEngine();
        return module;
    }

    /** Load and register a new module for this file. */
    @SuppressWarnings("UnusedReturnValue")
    public Module registerArchiveModule(Path path) throws IOException {
        Module module = moduleFactory.createArchiveModule(path.toFile());
        registry.add(module);
        ensureModulesDependOnEngine();
        return module;
    }

    public void resolveAndLoadEnvironment(Name... modules) {
        resolveAndLoadEnvironment(Arrays.asList(modules));
    }

    public void resolveAndLoadEnvironment(Iterable<Name> modules) {
        DependencyResolver resolver = new DependencyResolver(registry);
        ResolutionResult result = resolver.resolve(modules);

        if (!result.isSuccess()) {
            // TODO: worth its own exception class?
            throw new RuntimeException("Failed to resolve dependencies for " + modules);
        }

        loadEnvironment(result.getModules(), true);
    }

    public ModuleEnvironment loadEnvironment(Set<Module> modules, boolean asPrimary) {
        Set<Module> finalModules = Sets.newLinkedHashSet(modules);
        finalModules.add(engineModule);
        ModuleEnvironment newEnvironment;
        boolean permissiveSecurityEnabled = Boolean.parseBoolean(System.getProperty(SystemConfig.PERMISSIVE_SECURITY_ENABLED_PROPERTY));
        if (permissiveSecurityEnabled) {
            newEnvironment = new ModuleEnvironment(finalModules, wrappingPermissionProviderFactory);
        } else {
            newEnvironment = new ModuleEnvironment(finalModules, permissionProviderFactory);
        }
        if (asPrimary) {
            environment = newEnvironment;
        }
        return newEnvironment;
    }

    @Deprecated(/*since="4.4.0"*/)
    public ModuleMetadataJsonAdapter getModuleMetadataReader() {
        // FIXME: This has one use in MTE. Give MTE a better interface to use and remove
        //     the accessor for this.
        return metadataReader;
    }

    public ModuleFactory getModuleFactory() {
        return moduleFactory;
    }

    public PermissionProvider getPermissionProvider(Module module) {
        return permissionProviderFactory.createPermissionProviderFor(module, x -> false);
    }

    static File urlToFile(URL url) {
        URL fileUrl = url;
        if (url.getProtocol().equals("jar")) {
            try {
                JarURLConnection connection = (JarURLConnection) url.openConnection();
                fileUrl = connection.getJarFileURL();
            } catch (IOException e) {
                throw new RuntimeException("Failed to get file from " + url);
            }
        }
        try {
            return new File(fileUrl.toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException("Failed to get file from " + url, e);
        }
    };
}
