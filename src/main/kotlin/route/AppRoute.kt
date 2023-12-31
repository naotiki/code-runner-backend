package route

import com.github.dockerjava.api.async.ResultCallback.Adapter
import com.github.dockerjava.api.model.BuildResponseItem
import com.github.dockerjava.api.model.PullResponseItem
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.network.sockets.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.utils.io.core.*
import io.ktor.websocket.*
import kotlinx.coroutines.launch
import model.RespondSession.Companion.toRespondSession
import model.RunnerError
import model.RunnerEvent
import model.RunnerHostInfo
import org.koin.ktor.ext.inject
import repository.ConfigurationRepository
import repository.DockerRepository
import repository.RuntimeRepository
import repository.SessionRepository
import util.get
import java.lang.IllegalStateException
import java.net.InetAddress
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.LinkedHashSet
private val logger = KotlinLogging.logger {  }
fun Routing.appRoute() {
    val runtimeRepository by inject<RuntimeRepository>()
    val dockerRepository by inject<DockerRepository>()
    val configRepository by inject<ConfigurationRepository>()
    route("/host"){
        get {
            call.respond(
                RunnerHostInfo(InetAddress.getLocalHost().hostName,configRepository.get())
            )
        }
    }
    route("/config") {
        get {
            call.respond(configRepository.get())
        }
        post("/reload") {
            configRepository.reload()
            call.respond(HttpStatusCode.OK)
        }
    }
    route("/runtime") {
        get {
            call.respond(runtimeRepository.listRuntimes())
        }
        get("/{name}") {
            val name = call.parameters["name"]!!
            val runtime = runtimeRepository.searchRuntimeData(name) ?: kotlin.run {
                call.respond(HttpStatusCode.NotFound, "Not Found")
                return@get
            }
            call.respond(runtime)
        }
        post("/rebuild") {
            runtimeRepository.regenerateRuntimeList()
            val adaptors = mutableListOf<Adapter<BuildResponseItem>>()
            runtimeRepository.listContainerRuntimes().forEach {
                adaptors.add(dockerRepository.buildImage(it.dockerfile, it.id))
            }
            adaptors.forEach {
                it.awaitCompletion()
            }
            call.respond(HttpStatusCode.OK)
            println("End")
        }
        post("/pull") {
            runtimeRepository.regenerateRuntimeList()
            val adaptors = mutableListOf<Adapter<PullResponseItem>>()
            runtimeRepository.listContainerRuntimes().forEach {
                adaptors.add(dockerRepository.pullImage(it.id))
            }
            adaptors.forEach {
                it.awaitCompletion()
            }
            call.respond(HttpStatusCode.OK)
            println("End")
        }
        post("/reload") {
            runtimeRepository.regenerateRuntimeList()
            call.respond(runtimeRepository.listRuntimes())
        }
    }
    val sessionRepository by inject<SessionRepository>()
    post("/run") {
        val identifier = call.parameters["langAlias"]
        if (identifier == null) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        val allMultipartData = call.receiveMultipart().readAllParts()
        println(allMultipartData.map { it.name + it::class.simpleName })
        val src = allMultipartData.get<PartData.FormItem>("src")!!.value
        val input = allMultipartData.get<PartData.FormItem>("input")?.value
        logger.trace { src }
        logger.trace { input }
        val sessionData = sessionRepository.addQueue(identifier, src.encodeToByteArray(), input?.encodeToByteArray())
        if (sessionData == null) {
            call.respond(HttpStatusCode.NotFound, "Runtime NotFound")
            return@post
        }
        call.respond(sessionData.toRespondSession())
    }
    val connections = Collections.synchronizedSet<Connection?>(LinkedHashSet())
    webSocket("/run/{sessionId}") {
        val thisConnection = Connection(this)
        connections += thisConnection
        val sessionId = call.parameters["sessionId"]
        try {
            sessionRepository.run(sessionId!!) {
                logger.trace { "Event $it" }
                launch {
                    sendSerialized<RunnerEvent>(it)
                }
            }
        } catch (e: RunnerError) {
            sendSerialized<RunnerEvent>(RunnerEvent.Abort(e.phase, e))
            logger.trace{"Error:$e"}
        } catch (e: Throwable) {
            logger.error { e.message.toString()}
        } finally {
            logger.trace{"Removing $thisConnection!"}
            connections -= thisConnection
            sessionRepository.clean(sessionId!!)
        }
    }
}

class Connection(val session: DefaultWebSocketSession) {
    companion object {
        val lastId = AtomicInteger(0)
    }

    val name = "session${lastId.getAndIncrement()}"
}
