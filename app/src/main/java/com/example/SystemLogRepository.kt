package com.example

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SystemLogRepository {
    private val _logs = mutableListOf<String>()
    val logs: List<String> get() = synchronized(_logs) { _logs.toList() }

    private val _logFlow = MutableSharedFlow<String>(replay = 50)
    val logFlow: SharedFlow<String> = _logFlow.asSharedFlow()

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    init {
        log("SYS", "Logging Repository Initialized.")
    }

    fun log(tag: String, message: String) {
        val formattedMsg = synchronized(_logs) {
            val timestamp = dateFormat.format(Date())
            val msg = "[$timestamp] $tag: $message"
            _logs.add(msg)
            if (_logs.size > 200) {
                _logs.removeAt(0)
            }
            msg
        }
        _logFlow.tryEmit(formattedMsg)
    }

    fun clear() {
        synchronized(_logs) {
            _logs.clear()
        }
        _logFlow.tryEmit("[SYS] Logs cleared.")
    }
}
