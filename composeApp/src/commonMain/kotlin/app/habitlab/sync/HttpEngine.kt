package app.habitlab.sync

import io.ktor.client.HttpClient

expect fun createHttpClient(): HttpClient
