/*
 * Copyright (c) 2022 New Vector Ltd
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

package io.element.android.features.messages.impl.messagecomposer

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.libraries.textcomposer.MessageComposerMode
import io.element.android.wysiwyg.compose.RichTextEditorState

open class MessageComposerStateProvider : PreviewParameterProvider<MessageComposerState> {
    override val values: Sequence<MessageComposerState>
        get() = sequenceOf(
            aMessageComposerState(),
        )
}

fun aMessageComposerState(
    requestFocus: Boolean = true,
    composerState: RichTextEditorState = RichTextEditorState("", fake = true),
    isFullScreen: Boolean = false,
    mode: MessageComposerMode = MessageComposerMode.Normal(content = ""),
    showTextFormatting: Boolean = false,
    showAttachmentSourcePicker: Boolean = false,
    canShareLocation: Boolean = true,
    canCreatePoll: Boolean = true,
    attachmentsState: AttachmentsState = AttachmentsState.None,
) = MessageComposerState(
    richTextEditorState = composerState.apply { if(requestFocus) requestFocus() },
    isFullScreen = isFullScreen,
    mode = mode,
    showTextFormatting = showTextFormatting,
    showAttachmentSourcePicker = showAttachmentSourcePicker,
    canShareLocation = canShareLocation,
    canCreatePoll = canCreatePoll,
    attachmentsState = attachmentsState,
    eventSink = {},
)
