import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.content.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

fun main() {
    // Note: On Android, start this inside a Thread or Coroutine
    val dotenv = io.github.cdimascio.dotenv.dotenv()
    val apiUser = dotenv["SIGHTENGINE_USER"]
    val apiSecret = dotenv["SIGHTENGINE_SECRET"]

    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) {
            json() // This now resolves correctly
        }

        routing {
            post("/check-ai") {
                val multipart = call.receiveMultipart()
                var fileBytes: ByteArray? = null
                var fileName = "upload.jpg"

                multipart.forEachPart { part ->
                    if (part is PartData.FileItem) {
                        fileBytes = part.streamProvider().readBytes()
                        fileName = part.originalFileName ?: "upload.jpg"
                    }
                    part.dispose()
                }

                if (fileBytes == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No file uploaded"))
                    return@post
                }

                val result = withContext(Dispatchers.IO) {
                    val client = OkHttpClient()
                    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("media", fileName, fileBytes!!.toRequestBody("image/jpeg".toMediaType()))
                        .addFormDataPart("models", "genai")
                        .addFormDataPart("api_user", apiUser ?: "")
                        .addFormDataPart("api_secret", apiSecret ?: "")
                        .build()

                    val request = Request.Builder()
                        .url("https://api.sightengine.com/1.0/check.json")
                        .post(body)
                        .build()

                    client.newCall(request).execute().use { it.body?.string() ?: "{}" }
                }

                call.respondText(result, ContentType.Application.Json)
            }
        }
    }.start(wait = true)
}