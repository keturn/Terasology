// Copyright 2020 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.logistics.gooky

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.file
import org.eclipse.jgit.api.Git
import org.terasology.module.ModuleMetadataJsonAdapter


class ShowVersion: CliktCommand() {
    val dir by argument().file(mustExist = true, canBeFile = false)

    override fun run() {
        val moduleFile = dir.resolve("module.txt")
        val metadata = moduleFile.reader().use {
            ModuleMetadataJsonAdapter().read(it)!!
        }
        echo("${metadata.id}: " +
                "Name: \"${metadata.displayName}\" Version: ${metadata.version}")
    }
}


class GitStatus: CliktCommand() {
    val dir by argument().file(mustExist = true, canBeFile = false)

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


class Gooky: CliktCommand() {
    init {
        subcommands(GitStatus(), ShowVersion())
    }

    override fun run() = Unit
}


fun main(args: Array<String>) {
    Gooky().main(args)
}
