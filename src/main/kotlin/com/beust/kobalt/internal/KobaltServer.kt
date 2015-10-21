package com.beust.kobalt.internal

import com.beust.kobalt.Args
import com.beust.kobalt.kotlin.BuildFile
import com.beust.kobalt.kotlin.BuildFileCompiler
import com.beust.kobalt.maven.IClasspathDependency
import com.beust.kobalt.maven.MavenDependency
import com.beust.kobalt.misc.KobaltExecutors
import com.beust.kobalt.misc.log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.inject.Inject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.SocketException
import java.nio.file.Paths

public class KobaltServer @Inject constructor(val args: Args, val executors: KobaltExecutors,
        val buildFileCompilerFactory: BuildFileCompiler.IFactory) : Runnable {
    var outgoing: PrintWriter? = null
    val pending = arrayListOf<String>()

    override fun run() {
        val portNumber = args.port

        log1("Listening to port $portNumber")
        var quit = false
        val serverSocket = ServerSocket(portNumber)
        while (! quit) {
            val clientSocket = serverSocket.accept()
            outgoing = PrintWriter(clientSocket.outputStream, true)
            if (pending.size() > 0) {
                log1("Emptying the queue, size $pending.size()")
                synchronized(pending) {
                    pending.forEach { sendData(it) }
                    pending.clear()
                }
            }
            val ins = BufferedReader(InputStreamReader(clientSocket.inputStream))
            try {
                var line = ins.readLine()
                while (!quit && line != null) {
                    log1("Received from client $line")
                    val jo = JsonParser().parse(line) as JsonObject
                    if ("Quit" == jo.get("name").asString) {
                        log1("Quitting")
                        quit = true
                    } else {
                        runCommand(jo)
                        line = ins.readLine()
                    }
                }
            } catch(ex: SocketException) {
                log1("Client disconnected, resetting")
            }
        }
    }

    interface Command {
        fun run(jo: JsonObject)
    }

    class CommandData(val commandName: String, val data: String)

    inner class PingCommand() : Command {
        override fun run(jo: JsonObject) = sendData("{ \"response\" : \"${jo.toString()}\" }")
    }

    inner class GetDependenciesCommand() : Command {
        override fun run(jo: JsonObject) {
            val buildFile = BuildFile(Paths.get(jo.get("buildFile").asString), "GetDependenciesCommand")
            val scriptCompiler = buildFileCompilerFactory.create(listOf(buildFile))
            scriptCompiler.observable.subscribe {
                buildScriptInfo -> if (buildScriptInfo.projects.size() > 0) {
                    sendData(toJson(buildScriptInfo))
                }
            }
            scriptCompiler.compileBuildFiles(args)
            sendData("{ \"name\": \"Quit\" }")
        }
    }

    class DependencyData(val id: String, val scope: String, val path: String)

    class ProjectData( val name: String, val dependencies: List<DependencyData>)

    class GetDependenciesData(val projects: List<ProjectData>) {
        fun toData() : CommandData {
            val data = Gson().toJson(this)
            return CommandData("GetDependencies", data)
        }
    }

    private fun toJson(info: BuildFileCompiler.BuildScriptInfo) : String {
        val executor = executors.miscExecutor
        val projects = arrayListOf<ProjectData>()

        fun toDependencyData(d: IClasspathDependency, scope: String) : DependencyData {
            val dep = MavenDependency.create(d.id, executor)
            return DependencyData(d.id, scope, dep.jarFile.get().absolutePath)
        }

        info.projects.forEach { project ->
            val allDependencies =
                    project.compileDependencies.map { toDependencyData(it, "compile") } +
                    project.compileProvidedDependencies.map { toDependencyData(it, "provided") } +
                    project.compileRuntimeDependencies.map { toDependencyData(it, "runtime") } +
                    project.testDependencies.map { toDependencyData(it, "testCompile") } +
                    project.testProvidedDependencies.map { toDependencyData(it, "testProvided") }

            projects.add(ProjectData(project.name!!, allDependencies))
        }
        log1("Returning BuildScriptInfo")
        val result = Gson().toJson(GetDependenciesData(projects).toData())
        log2("  $result")
        return result
    }

    private val COMMANDS = hashMapOf<String, Command>(
            Pair("GetDependencies", GetDependenciesCommand())
    )

    private fun runCommand(jo: JsonObject) {
        val command = jo.get("name").asString
        if (command != null) {
            COMMANDS.getOrElse(command, { PingCommand() }).run(jo)
        } else {
            error("Did not find a name in command: $jo")
        }
    }

    fun sendData(info: String) {
        if (outgoing != null) {
            outgoing!!.println(info)
        } else {
            log1("Queuing $info")
            synchronized(pending) {
                pending.add(info)
            }
        }
    }

    private fun log1(s: String) {
        log(1, "[KobaltServer] $s")
    }

    private fun log2(s: String) {
        log(2, "[KobaltServer] $s")
    }
}


