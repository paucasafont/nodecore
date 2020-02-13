// VeriBlock NodeCore
// Copyright 2017-2019 Xenios SEZC
// All rights reserved.
// https://www.veriblock.org
// Distributed under the MIT software license, see the accompanying
// file LICENSE or http://www.opensource.org/licenses/mit-license.php.

package nodecore.miners.pop.api

import de.nielsfalk.ktor.swagger.SwaggerSupport
import de.nielsfalk.ktor.swagger.version.shared.Contact
import de.nielsfalk.ktor.swagger.version.shared.Information
import de.nielsfalk.ktor.swagger.version.v2.Swagger
import de.nielsfalk.ktor.swagger.version.v3.OpenApi
import io.ktor.application.install
import io.ktor.features.CallLogging
import io.ktor.features.ContentNegotiation
import io.ktor.features.DefaultHeaders
import io.ktor.gson.gson
import io.ktor.locations.Locations
import io.ktor.routing.routing
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import mu.KotlinLogging
import nodecore.miners.pop.api.controller.ApiController
import nodecore.miners.pop.api.controller.statusPages
import java.util.concurrent.TimeUnit

const val API_VERSION = "0.3"

class ApiServer(
    private val controllers: List<ApiController>
) {

    private var running = false

    var port: Int = 8080
        set(value) {
            if (running) {
                error("Port cannot be set after the server has started")
            }

            field = value
        }

    var server: ApplicationEngine? = null

    fun start() {
        if (running) {
            return
        }

        logger.info { "Starting HTTP API on port $port" }

        server = try {
            embeddedServer(Netty, port = port) {
                install(DefaultHeaders)
                install(CallLogging)

                statusPages()

                install(ContentNegotiation) {
                    gson()
                }


                // Documentation
                install(SwaggerSupport) {
                    forwardRoot = true
                    provideUi = true
                    val information = Information(
                        version = API_VERSION,
                        title = "VeriBlock PoP Miner API",
                        description = "This is the VPM's integrated API, through which you can monitor and control the application",
                        contact = Contact("VeriBlock", "https://veriblock.org")
                    )
                    swagger = Swagger().apply {
                        info = information
                    }
                    openApi = OpenApi().apply {
                        info = information
                    }
                }

                install(Locations)
                routing {
                    for (controller in controllers) {
                        with(controller) {
                            registerApi()
                        }
                    }
                }
            }.start()
        } catch (e: Exception) {
            logger.warn { "Could not start the API: ${e.message}" }
            return
        }

        running = true
    }

    fun shutdown() {
        if (!running) {
            return
        }

        server?.stop(100, 100, TimeUnit.MILLISECONDS)
        running = false
    }
}

private val logger = KotlinLogging.logger {}
