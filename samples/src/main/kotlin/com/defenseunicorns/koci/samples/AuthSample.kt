/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.samples

import com.defenseunicorns.koci.api.Koci
import com.defenseunicorns.koci.api.config.AuthConfig
import kotlinx.coroutines.runBlocking

fun main(): Unit = runBlocking {
  val url = prompt("Registry URL (e.g. https://registry.example.com): ")
  val user = prompt("Username: ")
  val pass = promptSecret("Password: ")

  Koci.create(root = "/tmp/koci-auth-sample").use { koci ->
    val registry = koci.registry(url = url, auth = AuthConfig.Basic(user = user, pass = pass))
    println("basic auth ping: ${registry.ping()}")
  }
}

private fun prompt(label: String): String {
  print(label)
  System.out.flush()
  return readlnOrNull().orEmpty().trim()
}

private fun promptSecret(label: String): String {
  val console = System.console()
  if (console != null) return console.readPassword(label).contentToString()
  return prompt(label)
}
