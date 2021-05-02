// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.engine.core.module;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.reflections.Reflections;
import org.terasology.engine.core.paths.PathManager;
import org.terasology.engine.core.subsystem.EngineSubsystem;
import org.terasology.engine.entitySystem.Component;
import org.terasology.engine.logic.permission.PermissionSetComponent;
import org.terasology.engine.world.block.structure.AttachSupportRequiredComponent;
import org.terasology.gestalt.module.Module;
import org.terasology.gestalt.module.ModuleEnvironment;
import org.terasology.gestalt.module.ModuleMetadata;
import org.terasology.gestalt.module.resources.EmptyFileSource;
import org.terasology.gestalt.module.sandbox.PermissionProvider;
import org.terasology.gestalt.naming.Name;
import org.terasology.gestalt.naming.Version;
import org.terasology.unittest.stubs.StubSubsystem;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.terasology.engine.core.TerasologyConstants.ENGINE_MODULE;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ModuleManagerTest {

    ModuleManager manager;
    ModuleEnvironment environment;
    Module engineModule;

    @BeforeEach
    public void provideEngineModule(@Mock PathManager pathManager) {
        // FIXME: when run on its own, mockito says this stub is unnecessary, but in a larger
        //    test run it is not. Thus the LENIENT setting. 🙁
        when(pathManager.getModulePaths()).thenReturn(Collections.emptyList());
        manager = new ModuleManager("") {
            {
                pathManagerFactory = () -> pathManager;
            }
        };
        environment = manager.getEnvironment();
        engineModule = environment.get(ENGINE_MODULE);

        // assert we did not load extra modules that may have been laying around.
        assertEquals(Collections.singleton(ENGINE_MODULE), manager.getRegistry().getModuleIds());
        assertEquals(Collections.singletonList(ENGINE_MODULE), environment.getModuleIdsOrderedByDependencies());
    }

    private Module getEmptyTestModule() {
        return new Module(
                new ModuleMetadata(new Name("EmptyTestModule"), new Version("0.0.1")),
                new EmptyFileSource(),
                Collections.emptyList(),
                new Reflections(),
                (clazz) -> false
        );
    }

    @Test
    public void nonApiClassesAreNotPermitted() {
        Class<?> disallowedClass = PermissionSetComponent.class;
        PermissionProvider permissionProvider = manager.getPermissionProvider(getEmptyTestModule());
        assertFalse(permissionProvider.isPermitted(disallowedClass));
    }

    @ParameterizedTest
    @ValueSource(classes = {
            Component.class,
            AttachSupportRequiredComponent.class,
            PermissionSetComponent.class
    })
    public void engineModuleContainsNonApiClasses(Class<?> clazz) {
        // These classes should be recognized as belonging to the engine module, even if access
        // to them is not permitted from other modules.
        assertEquals(ENGINE_MODULE, environment.getModuleProviding(clazz));
    }

    @Test
    public void engineModuleContainsSubsystems() {
        PathManager pathManager = mock(PathManager.class);
        when(pathManager.getModulePaths()).thenReturn(Collections.emptyList());
        Class<StubSubsystem> subsystem = StubSubsystem.class;
        manager = new ModuleManager("", Collections.singletonList(subsystem)) {
            {
                pathManagerFactory = () -> pathManager;
            }
        };
        environment = manager.getEnvironment();
        engineModule = environment.get(ENGINE_MODULE);

        assertTrue(ImmutableList.copyOf(
                engineModule.getModuleManifest().getSubTypesOf(EngineSubsystem.class))
                .contains(subsystem));

        assertTrue(ImmutableList.copyOf(environment.getSubtypesOf(EngineSubsystem.class)).contains(subsystem));

        assertEquals(ENGINE_MODULE, environment.getModuleProviding(subsystem));
    }
}
