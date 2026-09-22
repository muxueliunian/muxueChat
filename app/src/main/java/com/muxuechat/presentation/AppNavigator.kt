package com.muxuechat.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muxuechat.presentation.chat.ChatScreen
import com.muxuechat.presentation.chat.ChatViewModel
import com.muxuechat.presentation.settings.SettingsScreen
import com.muxuechat.presentation.settings.XaiAuthViewModel
import com.muxuechat.presentation.theme.MuxueChatTheme

private enum class Destination { Chat, Settings }

@Composable
fun MuxueChatApp(viewModel: ChatViewModel, xaiAuthViewModel: XaiAuthViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val xaiAuthState by xaiAuthViewModel.uiState.collectAsStateWithLifecycle()
    val saveableStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var destination by rememberSaveable { mutableStateOf(Destination.Chat) }

    // Settings 是同一 Activity 内的界面状态，不会自动进入 Android 返回栈。
    // 显式拦截系统返回，避免直接落到 Activity.finish()。
    BackHandler(enabled = destination == Destination.Settings) {
        destination = Destination.Chat
    }

    MuxueChatTheme(displayScale = state.displaySettings.displayScale) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            AnimatedContent(
                targetState = destination,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "destination",
            ) { target ->
                saveableStateHolder.SaveableStateProvider(target) {
                when (target) {
                    Destination.Chat -> ChatScreen(
                        state = state,
                        onSelectConversation = viewModel::selectConversation,
                        onSendMessage = viewModel::sendMessage,
                        onStopGeneration = viewModel::stopGeneration,
                        onRegenerate = viewModel::regenerateLastResponse,
                        onRetryRpOptions = viewModel::retryLatestRpOptions,
                        onDeleteConversation = viewModel::deleteConversation,
                        onRenameConversation = viewModel::renameConversation,
                        onClearConversation = viewModel::clearConversation,
                        onOpenSettings = { destination = Destination.Settings },
                        onUpdateRpSettings = { enabled,
                                roleCardId,
                                worldSettingId,
                                writingStyleId,
                                name,
                                description,
                                initialState,
                                narrativeMinChars,
                                narrativePerspective,
                                plotSuggestionPerspective,
                                rpMarkerMode ->
                            viewModel.updateActiveConversationRpSettings(
                                isRpEnabled = enabled,
                                roleCardId = roleCardId,
                                worldSettingId = worldSettingId,
                                writingStyleId = writingStyleId,
                                protagonistName = name,
                                protagonistDescription = description,
                                protagonistInitialState = initialState,
                                narrativeMinChars = narrativeMinChars,
                                narrativePerspective = narrativePerspective,
                                plotSuggestionPerspective = plotSuggestionPerspective,
                                rpMarkerMode = rpMarkerMode,
                            )
                        },
                        onSaveRoleCard = viewModel::saveRoleCard,
                        onDeleteRoleCard = viewModel::deleteRoleCard,
                        onSaveWorldSetting = viewModel::saveWorldSetting,
                        onDeleteWorldSetting = viewModel::deleteWorldSetting,
                        onSaveWritingStyle = viewModel::saveWritingStyleProfile,
                        onDeleteWritingStyle = viewModel::deleteWritingStyleProfile,
                        onEditUserMessage = viewModel::editUserMessageAndRegenerate,
                        onEditAssistantMessage = viewModel::editAssistantMessage,
                        onAttachDocument = viewModel::attachDocument,
                        onRemoveAttachment = viewModel::clearAttachment,
                        onCompactContextNow = viewModel::compactContextNow,
                        onCompressionModeChange = viewModel::updateCompressionMode,
                        onCompressionTriggerChange = viewModel::updateCompressionTriggerPercent,
                        onConfirmSettingsDraft = { confirmation ->
                            viewModel.confirmSettingsDraft(
                                draftId = confirmation.draftId,
                                roleCard = confirmation.roleCard,
                                worldSetting = confirmation.worldSetting,
                                writingStyle = confirmation.writingStyle,
                                bindToConversation = confirmation.bindToConversation,
                            )
                        },
                        onDiscardSettingsDraft = viewModel::discardSettingsDraft,
                        onSelectDeepSeekModel = viewModel::selectDeepSeekModel,
                        onSelectCustomProviderModel = viewModel::selectCustomProviderModel,
                        onRefreshProviderModels = viewModel::refreshProviderModels,
                    )
                    Destination.Settings -> SettingsScreen(
                        settings = state.settings,
                        displaySettings = state.displaySettings,
                        statusMessage = state.errorMessage,
                        refreshErrors = state.modelRefreshErrors,
                        onSettingsChange = viewModel::updateSettings,
                        onDisplayScaleChange = viewModel::updateDisplayScale,
                        onSaveApiKey = viewModel::saveApiKey,
                        onClearApiKey = viewModel::clearApiKey,
                        providersWithSavedKey = state.providersWithSavedKey,
                        refreshingModelsProviderId = state.refreshingModelsProviderId,
                        onAddProvider = viewModel::addCustomProvider,
                        onAddXaiProvider = viewModel::addXaiProvider,
                        onUpdateProvider = viewModel::updateCustomProvider,
                        onDeleteProvider = viewModel::deleteCustomProvider,
                        onSaveProviderKey = viewModel::saveProviderKey,
                        onClearProviderKey = viewModel::clearProviderKey,
                        onRefreshProviderModels = viewModel::refreshProviderModels,
                        xaiAuthState = xaiAuthState,
                        onXaiLogin = xaiAuthViewModel::startLogin,
                        onXaiCancelLogin = xaiAuthViewModel::cancelLogin,
                        onXaiSignOut = xaiAuthViewModel::signOut,
                        onXaiBrowserUnavailable = xaiAuthViewModel::reportBrowserUnavailable,
                        onBack = { destination = Destination.Chat },
                    )
                }
                }
            }
        }
    }
}
