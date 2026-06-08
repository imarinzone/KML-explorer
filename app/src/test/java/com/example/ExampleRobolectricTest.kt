package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun testProcessSharedMapsTextReal() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val result = processSharedMapsText(context, "https://maps.app.goo.gl/tQ3qQUzNS37eKTcH6")
    assertNotNull(result)
    assertTrue("Routing or elevation failed: ${result.errorMessage}", result.errorMessage == null)
    assertTrue("Expected to receive points on route resolution", result.points.isNotEmpty())
    assertTrue("Expected at least a few points", result.points.size > 20)
  }
}
