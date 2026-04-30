package com.contextai.presentation.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.contextai.data.local.ConversationDao
import com.contextai.domain.model.ConversationEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val conversationDao: ConversationDao
) : ViewModel() {

    val searchQuery = MutableStateFlow("")

    val conversations: StateFlow<List<ConversationEntity>> = searchQuery
        .debounce(300)
        .flatMapLatest { query ->
            if (query.isBlank()) {
                conversationDao.getAllConversations()
            } else {
                conversationDao.searchConversations(query)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteConversation(conversation: ConversationEntity) {
        viewModelScope.launch {
            runCatching { conversationDao.delete(conversation) }
        }
    }

    fun updateSearch(query: String) {
        searchQuery.value = query
    }
}
