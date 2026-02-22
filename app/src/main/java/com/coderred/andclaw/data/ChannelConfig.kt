package com.coderred.andclaw.data

data class ChannelConfig(
    val whatsappEnabled: Boolean = true,
    val telegramEnabled: Boolean = false,
    val telegramBotToken: String = "",
    val discordEnabled: Boolean = false,
    val discordBotToken: String = "",
    val discordGuildAllowlist: String = "",
    val discordRequireMention: Boolean = true,
)

enum class ChannelConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

data class ChannelStatus(
    val whatsapp: ChannelConnectionStatus = ChannelConnectionStatus.DISCONNECTED,
    val telegram: ChannelConnectionStatus = ChannelConnectionStatus.DISCONNECTED,
    val discord: ChannelConnectionStatus = ChannelConnectionStatus.DISCONNECTED,
)
