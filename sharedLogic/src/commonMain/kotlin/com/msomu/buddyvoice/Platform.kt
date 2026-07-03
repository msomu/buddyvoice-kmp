package com.msomu.buddyvoice

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform