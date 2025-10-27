/// *
// * Copyright 2024-2025 Defense Unicorns
// * SPDX-License-Identifier: Apache-2.0
// */
//
// package com.defenseunicorns.koci
//
// import com.defenseunicorns.koci.auth.ACTION_DELETE
// import com.defenseunicorns.koci.auth.ACTION_PULL
// import com.defenseunicorns.koci.auth.ACTION_PUSH
// import com.defenseunicorns.koci.auth.SCOPE_REGISTRY_CATALOG
// import com.defenseunicorns.koci.auth.scopeRepository
// import kotlin.test.Test
// import kotlin.test.assertEquals
//
// class ScopesTest {
//  @Test
//  @Suppress("detekt:LongMethod")
//  fun cleanScopes() {
//    val testCases =
//      listOf(
//        emptyList<String>() to emptyList(),
//        listOf("") to listOf(""),
//        listOf(SCOPE_REGISTRY_CATALOG) to listOf(SCOPE_REGISTRY_CATALOG),
//        listOf(scopeRepository("ubuntu", ACTION_PULL, ACTION_PUSH)) to
//          listOf(scopeRepository("ubuntu", ACTION_PULL, ACTION_PUSH)),
//        listOf("repository:foo:push,pull,delete") to listOf("repository:foo:delete,pull,push"),
//        listOf("repository:foo:push,pull,push,pull,push,push,pull") to
//          listOf("repository:foo:pull,push"),
//        listOf("repository:foo:pull,*,push") to listOf("repository:foo:*"),
//        listOf("repository:foo:,") to emptyList(),
//        listOf(scopeRepository("ubuntu", ACTION_PUSH), scopeRepository("ubuntu", ACTION_PULL)) to
//          listOf(scopeRepository("ubuntu", ACTION_PULL, ACTION_PUSH)),
//        listOf("repository:foo:pull", "repository:bar:push") to
//          listOf("repository:bar:push", "repository:foo:pull"),
//        listOf(
//          "repository:foo:pull",
//          "repository:bar:push",
//          "repository:foo:push",
//          "repository:bar:push,delete,pull",
//          "repository:bar:delete,pull",
//          "repository:foo:pull",
//          "registry:catalog:*",
//          "registry:catalog:pull",
//        ) to
//          listOf(
//            "registry:catalog:*",
//            "repository:bar:delete,pull,push",
//            "repository:foo:pull,push",
//          ),
//        listOf("repository:foo:,", "repository:bar:,") to emptyList(),
//        listOf("unknown") to listOf("unknown"),
//        listOf(
//          "repository:foo:pull",
//          "unknown",
//          "invalid:scope",
//          "no:actions:",
//          "repository:foo:push",
//        ) to listOf("invalid:scope", "repository:foo:pull,push", "unknown"),
//      )
//
//    for ((dirty, clean) in testCases) {
//      val actual = com.defenseunicorns.koci.auth.cleanScopes(dirty)
//      assertEquals(clean, actual)
//    }
//  }
//
//  @Test
//  fun cleanActions() {
//    val testCases =
//      listOf(
//        emptyList<String>() to emptyList(),
//        listOf("") to emptyList(),
//        listOf(ACTION_PULL) to listOf(ACTION_PULL),
//        listOf(ACTION_PULL, ACTION_PUSH) to listOf(ACTION_PULL, ACTION_PUSH),
//        listOf(ACTION_PULL, "", ACTION_PUSH) to listOf(ACTION_PULL, ACTION_PUSH),
//        listOf("", "", "") to emptyList(),
//        listOf(ACTION_PUSH, ACTION_PULL, ACTION_DELETE) to
//          listOf(ACTION_DELETE, ACTION_PULL, ACTION_PUSH),
//        listOf("*") to listOf("*"),
//        listOf("*", ACTION_PUSH, ACTION_PULL, ACTION_DELETE) to listOf("*"),
//        listOf(ACTION_PUSH, ACTION_PULL, "*", ACTION_DELETE) to listOf("*"),
//        listOf(ACTION_PUSH, ACTION_PULL, ACTION_DELETE, "*") to listOf("*"),
//      )
//
//    for ((actions, expected) in testCases) {
//      val actual = com.defenseunicorns.koci.auth.cleanActions(actions)
//      assertEquals(expected, actual)
//    }
//  }
// }
