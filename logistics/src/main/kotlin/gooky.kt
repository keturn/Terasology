// Copyright 2020 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.logistics.gooky

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.file
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.terasology.module.ModuleMetadata
import org.terasology.module.ModuleMetadataJsonAdapter
import org.terasology.naming.Version
import java.io.File


const val MODULE_INFO_NAME = "module.txt"
const val RELEASE_BRANCH = "master"
const val UNSTABLE_BRANCH = "develop"


class ShowVersion: CliktCommand(help = """
   Print the name and version of a module. 
""") {
    val dir by argument(name="directory", help="the directory containing the module source").file(mustExist = true, canBeFile = false)

    override fun run() {
        val metadata = loadModuleMetadata(dir)
        echo("${metadata.id}: " +
                "Name: \"${metadata.displayName}\" Version: ${metadata.version}")
    }
}


class GitStatus: CliktCommand(help = """
    Print something from some git commit.
    
    (Proof of concept for git interface.)
""") {
    val dir by argument(name="directory", help="the directory containing the module source").file(mustExist = true, canBeFile = false)

    override fun run() {
        Git.open(dir).use {git ->
            val log = git.log().call()
            val latest = log.first()
            val who = latest.authorIdent.name
            val datetime = latest.authorIdent.`when`
            echo("[${datetime}] ${who}: ${latest.shortMessage}")
        }
    }
}


class Release: CliktCommand(help = """
    Commit a stable release and increment version.
    
    git checkout develop
    git commit -m "remove -SNAPSHOT"
    git checkout master
    git merge develop
    git checkout develop
    git merge master
    git commit -m "bump version"
""") {

    val dir by argument(name="directory", help="the directory containing the module source").file(mustExist = true, canBeFile = false)

    override fun run() {
        Git.open(dir).use { git ->
            with(git.checkout()) {
                setName(UNSTABLE_BRANCH)
                call()  // not loving this as a "high-level" API
            }

            val releaseVersion = unSnapshot(dir)

            with(git.add()) {
                isUpdate = true
                addFilepattern(MODULE_INFO_NAME)
                call()
            }

            with(git.commit()) {
                message = "version ${releaseVersion}"
                call()
            }

            with(git.checkout()) {
                setName(RELEASE_BRANCH)
                call()
            }

            with(git.merge()) {
                setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
                include(git.repository.resolve(UNSTABLE_BRANCH))
                call()
            }

            val releaseTag = "v${releaseVersion}"
            with(git.tag()) {
                isAnnotated = true
                name = releaseTag
                message = "version ${releaseVersion}"
                call()
            }

            with(git.checkout()) {
                setName(UNSTABLE_BRANCH)
                call()
            }

            val unstableVersion = bumpAndSnapshot(dir)

            with(git.add()) {
                isUpdate = true
                addFilepattern(MODULE_INFO_NAME)
                call()
            }

            with(git.commit()) {
                message = "bump version ${unstableVersion}"
                call()
            }

            with(git.push()) {
                isDryRun = true  // TESTING MODE
                add(UNSTABLE_BRANCH)
                add(RELEASE_BRANCH)
                add(releaseTag)
                call()
            }
        }
    }
}


class Gooky: CliktCommand() {
    init {
        subcommands(Release(), ShowVersion(), GitStatus())
    }

    override fun run() = Unit
}


fun main(args: Array<String>) {
    Gooky().main(args)
}


fun loadModuleMetadata(directory: File) : ModuleMetadata {
    val moduleFile = directory.resolve(MODULE_INFO_NAME)
    return moduleFile.reader().use {
        ModuleMetadataJsonAdapter().read(it)!!
    }
}


fun saveModuleMetadata(directory: File, metadata: ModuleMetadata) {
    val moduleFile = directory.resolve(MODULE_INFO_NAME)
    moduleFile.writer().use {
        ModuleMetadataJsonAdapter().write(metadata, it)
    }
}


fun unSnapshot(directory: File): Version {
    val metadata = loadModuleMetadata(directory)
    val currentVersion = metadata.version
    if (!currentVersion.isSnapshot) {
        throw RuntimeException("not currently a snapshot!")
    }
    val newVersion = Version(
        currentVersion.major,
        currentVersion.minor,
        currentVersion.patch,
        false
    )
    metadata.version = newVersion
    saveModuleMetadata(directory, metadata)
    return newVersion
}


fun bumpAndSnapshot(directory: File): Version {
    val metadata = loadModuleMetadata(directory)
    val currentVersion = metadata.version
    if (currentVersion.isSnapshot) {
        throw RuntimeException("already a snapshot!")
    }

    val newVersion = currentVersion.nextPatchVersion.snapshot
    metadata.version = newVersion
    saveModuleMetadata(directory, metadata)
    return newVersion
}
