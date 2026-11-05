package com.markedoe.ondo.ui

import com.markedoe.ondo.data.OndoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

/**
 * A view model of [repository] once the repository has read what is saved: the view model starts that reading
 * ([OndoRepository.bootstrap]) as it is built, and the state read then replaces the playlists and the settings. A
 * playlist added or a setting changed before would be lost (a playlist no longer the active one), as the app, which
 * shows nothing to change before, never does. Tests that change the state right after building one use this.
 */
suspend fun readyViewModel(repository: OndoRepository, minutes: Long = 1_760_000_000_000L): OndoViewModel =
    OndoViewModel(repository, Labels(), computation = Dispatchers.Unconfined, minutes = flowOf(minutes))
        .also { model -> model.state.first { it.isReady } }
