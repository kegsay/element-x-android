/*
 * Copyright (c) 2023 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.element.android.appnav

import android.os.Parcelable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumble.appyx.core.composable.Children
import com.bumble.appyx.core.lifecycle.subscribe
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.plugin.Plugin
import com.bumble.appyx.core.plugin.plugins
import com.bumble.appyx.navmodel.backstack.BackStack
import com.bumble.appyx.navmodel.backstack.operation.pop
import com.bumble.appyx.navmodel.backstack.operation.push
import com.bumble.appyx.navmodel.backstack.operation.replace
import com.bumble.appyx.navmodel.backstack.operation.singleTop
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.element.android.anvilannotations.ContributesNode
import io.element.android.appnav.loggedin.LoggedInNode
import io.element.android.appnav.room.RoomFlowNode
import io.element.android.appnav.room.RoomLoadedFlowNode
import io.element.android.features.createroom.api.CreateRoomEntryPoint
import io.element.android.features.ftue.api.FtueEntryPoint
import io.element.android.features.ftue.api.state.FtueState
import io.element.android.features.invitelist.api.InviteListEntryPoint
import io.element.android.features.networkmonitor.api.NetworkMonitor
import io.element.android.features.networkmonitor.api.NetworkStatus
import io.element.android.features.preferences.api.PreferencesEntryPoint
import io.element.android.features.roomlist.api.RoomListEntryPoint
import io.element.android.features.verifysession.api.VerifySessionEntryPoint
import io.element.android.libraries.architecture.BackstackNode
import io.element.android.libraries.architecture.animation.rememberDefaultTransitionHandler
import io.element.android.libraries.architecture.createNode
import io.element.android.libraries.architecture.waitForChildAttached
import io.element.android.libraries.deeplink.DeeplinkData
import io.element.android.libraries.designsystem.utils.SnackbarDispatcher
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.MAIN_SPACE
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.sync.SyncState
import io.element.android.libraries.push.api.notifications.NotificationDrawerManager
import io.element.android.services.appnavstate.api.AppNavigationStateService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import timber.log.Timber

@ContributesNode(SessionScope::class)
class LoggedInFlowNode @AssistedInject constructor(
    @Assisted buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    private val roomListEntryPoint: RoomListEntryPoint,
    private val preferencesEntryPoint: PreferencesEntryPoint,
    private val createRoomEntryPoint: CreateRoomEntryPoint,
    private val appNavigationStateService: AppNavigationStateService,
    private val verifySessionEntryPoint: VerifySessionEntryPoint,
    private val inviteListEntryPoint: InviteListEntryPoint,
    private val ftueEntryPoint: FtueEntryPoint,
    private val coroutineScope: CoroutineScope,
    private val networkMonitor: NetworkMonitor,
    private val notificationDrawerManager: NotificationDrawerManager,
    private val ftueState: FtueState,
    private val matrixClient: MatrixClient,
    snackbarDispatcher: SnackbarDispatcher,
) : BackstackNode<LoggedInFlowNode.NavTarget>(
    backstack = BackStack(
        initialElement = NavTarget.RoomList,
        savedStateMap = buildContext.savedStateMap,
    ),
    buildContext = buildContext,
    plugins = plugins
) {

    interface Callback : Plugin {
        fun onOpenBugReport()
    }

    private val syncService = matrixClient.syncService()
    private val loggedInFlowProcessor = LoggedInEventProcessor(
        snackbarDispatcher,
        matrixClient.roomMembershipObserver(),
        matrixClient.sessionVerificationService(),
    )

    override fun onBuilt() {
        super.onBuilt()
        lifecycle.subscribe(
            onCreate = {
                appNavigationStateService.onNavigateToSession(id, matrixClient.sessionId)
                // TODO We do not support Space yet, so directly navigate to main space
                appNavigationStateService.onNavigateToSpace(id, MAIN_SPACE)
                loggedInFlowProcessor.observeEvents(coroutineScope)

                if (ftueState.shouldDisplayFlow.value) {
                    backstack.push(NavTarget.Ftue)
                }
            },
            onStop = {
                //Counterpart startSync is done in observeSyncStateAndNetworkStatus method.
                coroutineScope.launch {
                    syncService.stopSync()
                }
            },
            onDestroy = {
                appNavigationStateService.onLeavingSpace(id)
                appNavigationStateService.onLeavingSession(id)
                loggedInFlowProcessor.stopObserving()
            }
        )
        observeSyncStateAndNetworkStatus()
    }

    @OptIn(FlowPreview::class)
    private fun observeSyncStateAndNetworkStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    // small debounce to avoid spamming startSync when the state is changing quickly in case of error.
                    syncService.syncState.debounce(100),
                    networkMonitor.connectivity
                ) { syncState, networkStatus ->
                    Pair(syncState, networkStatus)
                }
                    .collect { (syncState, networkStatus) ->
                        Timber.d("Sync state: $syncState, network status: $networkStatus")
                        if (syncState != SyncState.Running && networkStatus == NetworkStatus.Online) {
                            syncService.startSync()
                        }
                    }
            }
        }
    }

    sealed interface NavTarget : Parcelable {
        @Parcelize
        data object Permanent : NavTarget

        @Parcelize
        data object RoomList : NavTarget

        @Parcelize
        data class Room(
            val roomId: RoomId,
            val initialElement: RoomLoadedFlowNode.NavTarget = RoomLoadedFlowNode.NavTarget.Messages
        ) : NavTarget

        @Parcelize
        data object Settings : NavTarget

        @Parcelize
        data object CreateRoom : NavTarget

        @Parcelize
        data object VerifySession : NavTarget

        @Parcelize
        data object InviteList : NavTarget

        @Parcelize
        data object Ftue : NavTarget
    }

    override fun resolve(navTarget: NavTarget, buildContext: BuildContext): Node {
        return when (navTarget) {
            NavTarget.Permanent -> {
                createNode<LoggedInNode>(buildContext)
            }
            NavTarget.RoomList -> {
                val callback = object : RoomListEntryPoint.Callback {
                    override fun onRoomClicked(roomId: RoomId) {
                        backstack.push(NavTarget.Room(roomId))
                    }

                    override fun onSettingsClicked() {
                        backstack.push(NavTarget.Settings)
                    }

                    override fun onCreateRoomClicked() {
                        backstack.push(NavTarget.CreateRoom)
                    }

                    override fun onSessionVerificationClicked() {
                        backstack.push(NavTarget.VerifySession)
                    }

                    override fun onInvitesClicked() {
                        backstack.push(NavTarget.InviteList)
                    }

                    override fun onRoomSettingsClicked(roomId: RoomId) {
                        backstack.push(NavTarget.Room(roomId, initialElement = RoomLoadedFlowNode.NavTarget.RoomDetails))
                    }

                    override fun onReportBugClicked() {
                        plugins<Callback>().forEach { it.onOpenBugReport() }
                    }
                }
                roomListEntryPoint
                    .nodeBuilder(this, buildContext)
                    .callback(callback)
                    .build()
            }
            is NavTarget.Room -> {
                val callback = object : RoomLoadedFlowNode.Callback {
                    override fun onForwardedToSingleRoom(roomId: RoomId) {
                        coroutineScope.launch { attachRoom(roomId) }
                    }
                }
                val inputs = RoomFlowNode.Inputs(roomId = navTarget.roomId, initialElement = navTarget.initialElement)
                createNode<RoomFlowNode>(buildContext, plugins = listOf(inputs, callback))
            }
            NavTarget.Settings -> {
                val callback = object : PreferencesEntryPoint.Callback {
                    override fun onOpenBugReport() {
                        plugins<Callback>().forEach { it.onOpenBugReport() }
                    }

                    override fun onVerifyClicked() {
                        backstack.push(NavTarget.VerifySession)
                    }
                }
                preferencesEntryPoint.nodeBuilder(this, buildContext)
                    .callback(callback)
                    .build()
            }
            NavTarget.CreateRoom -> {
                val callback = object : CreateRoomEntryPoint.Callback {
                    override fun onSuccess(roomId: RoomId) {
                        backstack.replace(NavTarget.Room(roomId))
                    }
                }

                createRoomEntryPoint
                    .nodeBuilder(this, buildContext)
                    .callback(callback)
                    .build()
            }
            NavTarget.VerifySession -> {
                verifySessionEntryPoint.createNode(this, buildContext)
            }
            NavTarget.InviteList -> {
                val callback = object : InviteListEntryPoint.Callback {
                    override fun onBackClicked() {
                        backstack.pop()
                    }

                    override fun onInviteAccepted(roomId: RoomId) {
                        backstack.push(NavTarget.Room(roomId))
                    }
                }

                inviteListEntryPoint.nodeBuilder(this, buildContext)
                    .callback(callback)
                    .build()
            }
            NavTarget.Ftue -> {
                ftueEntryPoint.nodeBuilder(this, buildContext)
                    .callback(object : FtueEntryPoint.Callback {
                        override fun onFtueFlowFinished() {
                            backstack.pop()
                        }
                    })
                    .build()
            }
        }
    }

    suspend fun attachRoot(): Node {
        return attachChild {
            backstack.singleTop(NavTarget.RoomList)
        }
    }

    suspend fun attachRoom(roomId: RoomId): RoomFlowNode {
        return attachChild {
            backstack.singleTop(NavTarget.RoomList)
            backstack.push(NavTarget.Room(roomId))
        }
    }

    internal suspend fun attachInviteList(deeplinkData: DeeplinkData.InviteList) = withContext(lifecycleScope.coroutineContext) {
        notificationDrawerManager.clearMembershipNotificationForSession(deeplinkData.sessionId)
        backstack.singleTop(NavTarget.RoomList)
        backstack.push(NavTarget.InviteList)
        waitForChildAttached<Node, NavTarget> { navTarget ->
            navTarget is NavTarget.InviteList
        }
    }

    @Composable
    override fun View(modifier: Modifier) {
        Box(modifier = modifier) {
            Children(
                navModel = backstack,
                modifier = Modifier,
                // Animate navigation to settings and to a room
                transitionHandler = rememberDefaultTransitionHandler(),
            )

            val isFtueDisplayed by ftueState.shouldDisplayFlow.collectAsState()

            if (!isFtueDisplayed) {
                PermanentChild(navTarget = NavTarget.Permanent)
            }
        }
    }
}
