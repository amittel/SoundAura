/*
 * This file is part of SoundAura, which is released under the terms of the Apache
 * License 2.0. See license.md in the project's root directory to see the full license.
 */

package com.cliffracertech.soundaura

import com.cliffracertech.soundaura.Dispatcher.setIO
import com.cliffracertech.soundaura.Dispatcher.setImmediate
import com.cliffracertech.soundaura.Dispatcher.setMain
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.annotations.VisibleForTesting

/** Launch a new coroutine, using the current value of [Dispatcher.IO] as the dispatcher. */
fun CoroutineScope.launchIO(block: suspend CoroutineScope.() -> Unit) =
    launch(Dispatcher.IO, block = block)

/** Like the standard [Dispatchers] object, except that the [setMain],
 * [setImmediate], and [setIO] functions allow overriding their respective
 * default dispatcher. */
object Dispatcher {
    var Main: CoroutineDispatcher = Dispatchers.Main
        private set
    var Immediate: CoroutineDispatcher = Dispatchers.Main.immediate
        private set
    var IO: CoroutineDispatcher = Dispatchers.IO
        private set

    @VisibleForTesting
    fun setMain(dispatcher: CoroutineDispatcher) { Main = dispatcher }
    @VisibleForTesting
    fun resetMain() { Main = Dispatchers.Main }

    @VisibleForTesting
    fun setImmediate(dispatcher: CoroutineDispatcher) { Immediate = dispatcher }
    @VisibleForTesting
    fun resetImmediate() { Immediate = Dispatchers.Main.immediate }

    @VisibleForTesting
    fun setIO(dispatcher: CoroutineDispatcher) { IO = dispatcher }
    @VisibleForTesting
    fun resetIO() { IO = Dispatchers.IO }
}