package com.beust.kobalt.app.remote

import com.beust.kobalt.Args
import com.beust.kobalt.api.Kobalt
import com.beust.kobalt.app.ProjectFinder
import com.beust.kobalt.internal.build.BuildSources
import com.beust.kobalt.internal.eventbus.ArtifactDownloadedEvent
import com.beust.kobalt.maven.aether.Exceptions
import com.beust.kobalt.misc.KFiles
import com.google.common.eventbus.EventBus
import com.google.common.eventbus.Subscribe
import com.google.gson.Gson
import org.eclipse.jetty.websocket.api.RemoteEndpoint
import org.eclipse.jetty.websocket.api.Session
import org.eclipse.jetty.websocket.api.WebSocketListener
import java.io.File
import java.nio.file.Paths

/**
 * Manage the websocket endpoint "/v1/getDependencyGraph".
 */
class GetDependencyGraphHandler : WebSocketListener {
    // The SparkJava project refused to merge https://github.com/perwendel/spark/pull/383
    // so I have to do dependency injections manually :-(
    val projectFinder = Kobalt.INJECTOR.getInstance(ProjectFinder::class.java)

    val PARAMETER_PROJECT_ROOT = "projectRoot"
    val PARAMETER_BUILD_FILE = "buildFile"

    var session: Session? = null

    override fun onWebSocketClose(code: Int, reason: String?) {
        println("ON CLOSE $code reason: $reason")
    }

    override fun onWebSocketError(cause: Throwable?) {
        Exceptions.printStackTrace(cause!!)
        throw UnsupportedOperationException()
    }

    fun <T> sendWebsocketCommand(endpoint: RemoteEndpoint, commandName: String, payload: T,
            errorMessage: String? = null) {
        endpoint.sendString(Gson().toJson(WebSocketCommand(commandName, payload = Gson().toJson(payload),
                errorMessage = errorMessage)))
    }

    private fun findBuildFile(map: Map<String, List<String>>) : BuildSources? {
        val projectRoot = map[PARAMETER_PROJECT_ROOT]
        val buildFile = map[PARAMETER_BUILD_FILE]
        val result =
            if (projectRoot != null) {
                BuildSources(File(projectRoot[0]))
            } else if (buildFile != null) {
                BuildSources(File(buildFile[0]))
            } else {
                null
            }
        return result
    }

    override fun onWebSocketConnect(s: Session) {
        session = s
        val buildSources = findBuildFile(s.upgradeRequest.parameterMap)

        fun <T> getInstance(cls: Class<T>) : T = Kobalt.INJECTOR.getInstance(cls)

        // Parse the request
        val result =
            if (buildSources != null) {
                // Track all the downloads that this dependency call might trigger and
                // send them as a progress message to the web socket
                val eventBus = getInstance(EventBus::class.java)
                val busListener = object {
                    @Subscribe
                    fun onArtifactDownloaded(event: ArtifactDownloadedEvent) {
                        sendWebsocketCommand(s.remote, ProgressCommand.NAME,
                                ProgressCommand(null, "Downloaded " + event.artifactId))
                    }
                }
                eventBus.register(busListener)

                // Get the dependencies for the requested build file and send progress to the web
                // socket for each project
                try {
                    val dependencyData = getInstance(RemoteDependencyData::class.java)
                    val args = getInstance(Args::class.java)

                    val allProjects = projectFinder.initForBuildFile(buildSources, args)

                    dependencyData.dependenciesDataFor(buildSources, args, object : IProgressListener {
                        override fun onProgress(progress: Int?, message: String?) {
                            sendWebsocketCommand(s.remote, ProgressCommand.NAME, ProgressCommand(progress, message))
                        }
                    }, useGraph = true)
                } catch(ex: Throwable) {
                    Exceptions.printStackTrace(ex)
                    RemoteDependencyData.GetDependenciesData(errorMessage = ex.message)
                } finally {
                    SparkServer.cleanUpCallback()
                    eventBus.unregister(busListener)
                }
            } else {
                RemoteDependencyData.GetDependenciesData(
                        errorMessage = "buildFile wasn't passed in the query parameter")
            }

        // Respond to the request
        sendWebsocketCommand(s.remote, RemoteDependencyData.GetDependenciesData.NAME, result,
                errorMessage = result.errorMessage)
        s.close()
    }

    override fun onWebSocketText(message: String?) {
        println("RECEIVED TEXT: $message")
        session?.remote?.sendString("Response: $message")
    }

    override fun onWebSocketBinary(payload: ByteArray?, offset: Int, len: Int) {
        println("RECEIVED BINARY: $payload")
    }

}
