package com.example.familysafety.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.storage.ChatMessageEntity
import com.example.familysafety.storage.ConversationSummary
import com.example.familysafety.storage.MessageType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for chat functionality.
 * Handles both conversation list and individual conversation screens.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val groupStateManager: GroupStateManager
) : ViewModel() {

    // =========================================================================
    // CONVERSATION LIST STATE
    // =========================================================================

    private val allSummaries = chatRepository.observeConversations()

    /**
     * Private (one-to-one) conversations, newest first. A conversation with someone who
     * has since left the family is dropped: there is no one to reply to.
     */
    val conversations: StateFlow<List<ConversationWithMember>> = allSummaries
        .combine(groupStateManager.groupDefinition) { conversations, group ->
            conversations.mapNotNull { summary ->
                if (summary.conversationId == group?.groupId) return@mapNotNull null
                val member = group?.findMemberById(summary.otherMemberId)
                if (member != null) {
                    ConversationWithMember(summary, member)
                } else null
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Last message and unread count of the family chat, or null before its first message. */
    val groupConversation: StateFlow<ConversationSummary?> = allSummaries
        .combine(groupStateManager.groupDefinition) { conversations, group ->
            conversations.firstOrNull { it.conversationId == group?.groupId }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Total unread message count.
     */
    val totalUnreadCount: StateFlow<Int> = chatRepository
        .observeTotalUnreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /**
     * Family members available to chat with.
     */
    val availableMembers: StateFlow<List<FamilyMember>> = groupStateManager.groupDefinition
        .map { group ->
            val localMemberId = groupStateManager.localMember.value?.memberId
            group?.members?.filter { it.memberId != localMemberId }?.toList() ?: emptyList()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // =========================================================================
    // SINGLE CONVERSATION STATE
    // =========================================================================

    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()

    private val _currentRecipient = MutableStateFlow<FamilyMember?>(null)
    val currentRecipient: StateFlow<FamilyMember?> = _currentRecipient.asStateFlow()

    /** True when the open conversation is the whole-group chat. */
    val isGroupConversation: StateFlow<Boolean> = _currentConversationId
        .map { convId -> convId != null && convId == groupStateManager.groupDefinition.value?.groupId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Map of memberId → displayName for showing sender names in group chat. */
    val memberNames: StateFlow<Map<String, String>> = groupStateManager.groupDefinition
        .map { group -> group?.members?.associate { it.memberId to it.displayName } ?: emptyMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Map of memberId → colorHue (null = derive from ID hash) for bubble/avatar coloring. */
    val memberColorHues: StateFlow<Map<String, Float?>> = groupStateManager.groupDefinition
        .map { group -> group?.members?.associate { it.memberId to it.colorHue } ?: emptyMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * Messages in current conversation.
     */
    val currentMessages: StateFlow<List<ChatMessageEntity>> = _currentConversationId
        .flatMapLatest { conversationId ->
            if (conversationId != null) {
                chatRepository.observeConversation(conversationId)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // =========================================================================
    // MESSAGE INPUT STATE
    // =========================================================================

    private val _messageInput = MutableStateFlow("")
    val messageInput: StateFlow<String> = _messageInput.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    // =========================================================================
    // EVENTS
    // =========================================================================

    private val _events = MutableSharedFlow<ChatEvent>()
    val events: SharedFlow<ChatEvent> = _events.asSharedFlow()

    // =========================================================================
    // CONVERSATION LIST ACTIONS
    // =========================================================================

    /**
     * Open the group chat (all members in one thread).
     */
    fun openGroupConversation() {
        viewModelScope.launch {
            // Wait for the group to be loaded — .value may be null on first launch.
            val groupId = groupStateManager.groupDefinition
                .filterNotNull()
                .first()
                .groupId
            _currentConversationId.value = groupId
            _currentRecipient.value = null
            chatRepository.setActiveConversation(groupId)
        }
    }

    /**
     * Open a conversation with a member.
     */
    fun openConversation(memberId: String) {
        viewModelScope.launch {
            // Same wait as the group chat: opened from a notification on a cold start,
            // the group may not be loaded yet, and that is not "member not found".
            val group = groupStateManager.groupDefinition.filterNotNull().first()
            val member = group.findMemberById(memberId)
            if (member == null) {
                _events.emit(ChatEvent.Error("Member not found"))
                return@launch
            }

            val conversationId = chatRepository.getConversationId(memberId)
            _currentConversationId.value = conversationId
            _currentRecipient.value = member
            chatRepository.setActiveConversation(conversationId)
        }
    }

    /**
     * The screen left the foreground while a conversation stays selected. Releases the
     * repository's "on screen" flag so new messages notify and stay unread, without
     * clearing what this screen shows.
     */
    fun pauseConversation() {
        chatRepository.setActiveConversation(null)
    }

    /**
     * Close current conversation.
     */
    fun closeConversation() {
        _currentConversationId.value = null
        _currentRecipient.value = null
        _messageInput.value = ""
        chatRepository.setActiveConversation(null)
    }

    /**
     * Delete a conversation.
     */
    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            chatRepository.deleteConversation(conversationId)
            _events.emit(ChatEvent.ConversationDeleted)
        }
    }

    // =========================================================================
    // MESSAGE ACTIONS
    // =========================================================================

    /**
     * Update message input.
     */
    fun onMessageInputChanged(text: String) {
        _messageInput.value = text
    }

    /**
     * Send the current message.
     */
    fun sendMessage() {
        val content = _messageInput.value.trim()
        if (content.isEmpty()) return

        val recipientId = _currentRecipient.value?.memberId
        val groupId = if (recipientId == null) groupStateManager.groupDefinition.value?.groupId else null

        if (recipientId == null && groupId == null) return

        viewModelScope.launch {
            _isSending.value = true
            _messageInput.value = ""

            val result = if (recipientId != null) {
                chatRepository.sendTextMessage(recipientId, content)
            } else {
                chatRepository.sendGroupTextMessage(groupId!!, content)
            }

            _isSending.value = false

            result.fold(
                onSuccess = { _events.emit(ChatEvent.MessageSent) },
                onFailure = { error -> _events.emit(ChatEvent.Error("Failed to send: ${error.message}")) }
            )
        }
    }

    /**
     * Share current location in chat.
     */
    fun shareLocation(latitude: Double, longitude: Double) {
        val recipientId = _currentRecipient.value?.memberId ?: return

        viewModelScope.launch {
            _isSending.value = true

            val result = chatRepository.sendLocationMessage(recipientId, latitude, longitude)

            _isSending.value = false

            result.fold(
                onSuccess = {
                    _events.emit(ChatEvent.MessageSent)
                },
                onFailure = { error ->
                    _events.emit(ChatEvent.Error("Failed to share location: ${error.message}"))
                }
            )
        }
    }

    /**
     * Delete a specific message.
     */
    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            chatRepository.deleteMessage(messageId)
        }
    }

    /**
     * Retry sending failed messages.
     */
    fun retryFailedMessages() {
        viewModelScope.launch {
            chatRepository.retrySendingFailedMessages()
        }
    }
}

/**
 * Conversation summary with member info.
 */
data class ConversationWithMember(
    val summary: ConversationSummary,
    val member: FamilyMember
)

/**
 * Events emitted by ChatViewModel.
 */
sealed class ChatEvent {
    object MessageSent : ChatEvent()
    object ConversationDeleted : ChatEvent()
    data class Error(val message: String) : ChatEvent()
}
