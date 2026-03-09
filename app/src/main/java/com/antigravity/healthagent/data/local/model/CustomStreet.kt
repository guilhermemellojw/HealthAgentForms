package com.antigravity.healthagent.data.local.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "custom_streets")
data class CustomStreet(
    @PrimaryKey
    val name: String,
    val bairro: String
)
