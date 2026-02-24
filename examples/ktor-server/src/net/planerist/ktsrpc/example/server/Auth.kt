package net.planerist.ktsrpc.example.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.*

/**
 * JWT configuration for the example server.
 * In production, these values would come from environment variables.
 */
object JwtConfig {
    const val SECRET = "yaranga-rpc-example-secret"
    const val ISSUER = "yaranga-rpc-example"
    const val AUDIENCE = "yaranga-rpc-example"
    const val REALM = "yaranga-rpc"

    private val algorithm = Algorithm.HMAC256(SECRET)

    fun makeToken(userId: Long): String = JWT.create()
        .withIssuer(ISSUER)
        .withAudience(AUDIENCE)
        .withClaim("userId", userId)
        .withExpiresAt(Date(System.currentTimeMillis() + 3_600_000)) // 1 hour
        .sign(algorithm)

    val verifier = JWT.require(algorithm)
        .withIssuer(ISSUER)
        .withAudience(AUDIENCE)
        .build()
}

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class TokenResponse(val token: String)

/**
 * Installs JWT authentication and a /auth/login endpoint.
 *
 * Demo users: "alice" / "password1" (userId=1), "bob" / "password2" (userId=2)
 */
fun Application.configureAuth() {
    install(Authentication) {
        jwt("auth-jwt") {
            realm = JwtConfig.REALM
            verifier(JwtConfig.verifier)
            validate { credential ->
                if (credential.payload.audience.contains(JwtConfig.AUDIENCE)) {
                    JWTPrincipal(credential.payload)
                } else null
            }
        }
    }

    routing {
        post("/auth/login") {
            val login = call.receive<LoginRequest>()

            // Demo user database
            val userId = when {
                login.username == "alice" && login.password == "password1" -> 1L
                login.username == "bob" && login.password == "password2" -> 2L
                else -> {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid credentials"))
                    return@post
                }
            }

            call.respond(TokenResponse(token = JwtConfig.makeToken(userId)))
        }
    }
}
