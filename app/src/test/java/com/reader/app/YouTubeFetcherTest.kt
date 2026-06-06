package com.reader.app

import com.reader.app.domain.youtube.YouTubeTranscriptFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Test

class YouTubeFetcherTest {
    @Test
    fun testFetchYouTubeTranscript() = runBlocking {
        println("--- Starting YouTube Fetcher Test ---")
        val testUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        println("Testing URL: $testUrl")
        
        val result = YouTubeTranscriptFetcher.fetch(testUrl)
        println("Result class: ${result.javaClass.simpleName}")
        when (result) {
            is YouTubeTranscriptFetcher.Result.Ok -> {
                println("SUCCESS: Title = ${result.title}")
                println("Transcript Length = ${result.transcript.length}")
                println("Cues Count = ${result.cues.size}")
                println("Language = ${result.language}")
                println("Source = ${result.source}")
                if (result.transcript.isNotEmpty()) {
                    println("Sample: ${result.transcript.take(200)}...")
                }
            }
            is YouTubeTranscriptFetcher.Result.Reject -> {
                println("REJECTED: Reason = ${result.reason}")
            }
        }
        println("--- End of Test ---")
    }
}
