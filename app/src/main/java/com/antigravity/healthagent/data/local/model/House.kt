package com.antigravity.healthagent.data.local.model

import androidx.room.*
import androidx.compose.runtime.Immutable
import com.antigravity.healthagent.domain.model.DailyContext
import com.antigravity.healthagent.domain.model.GeoCapture
import com.antigravity.healthagent.domain.model.TreatmentData
import com.antigravity.healthagent.domain.model.VisitAddress
import com.antigravity.healthagent.utils.normalize
import com.antigravity.healthagent.utils.toDashDate

@com.google.firebase.firestore.IgnoreExtraProperties
@Entity(
    tableName = "houses",
    indices = [
        Index(
            value = ["agentUid", "agentName", "data", "blockNumber", "blockSequence", "streetName", "number", "sequence", "complement", "bairro", "visitSegment"],
            unique = false
        ),
        Index(value = ["data", "agentUid"])
    ]
)
@Immutable
data class House(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @Embedded val address: VisitAddress = VisitAddress(),
    @Embedded val treatment: TreatmentData = TreatmentData(),
    @Embedded val context: DailyContext = DailyContext(),
    @Embedded val geo: GeoCapture = GeoCapture(),
    @ColumnInfo(defaultValue = "'EMPTY'") val propertyType: PropertyType = PropertyType.EMPTY,
    @ColumnInfo(defaultValue = "'EMPTY'") val situation: Situation = Situation.EMPTY,
    val data: String = "",
    val agentName: String = "",
    @ColumnInfo(defaultValue = "0") val localidadeConcluida: Boolean = false,
    @ColumnInfo(defaultValue = "0") val quarteiraoConcluido: Boolean = false,
    @ColumnInfo(defaultValue = "0") val listOrder: Long = 0, // For manual reordering
    @ColumnInfo(defaultValue = "0") val visitSegment: Int = 0, // To distinguish return trips to the same street
    @ColumnInfo(defaultValue = "''") val agentUid: String = "", // Crucial for multi-agent data isolation
    @ColumnInfo(defaultValue = "''") val observation: String = "", // Agent notes for the visit
    @ColumnInfo(defaultValue = "0") val createdAt: Long = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val isSynced: Boolean = false,
    @ColumnInfo(defaultValue = "0") val editedByAdmin: Boolean = false,
    @ColumnInfo(defaultValue = "''") val uuid: String = "",
    /**
     * Timestamp of the last local or remote modification.
     * 
     * WARNING: This field is @Excluded from Firestore automatic serialization to prevent 
     * local device timestamps from being pushed to the cloud. During sync (Push), 
     * Firestore's serverTimestamp() is used instead. 
     * 
     * During Pull, this field MUST be manually extracted from the DocumentSnapshot 
     * in SyncRepositoryImpl to ensure proper conflict resolution (Last-Write-Wins).
     */
    @get:com.google.firebase.firestore.Exclude
    @ColumnInfo(defaultValue = "0") val lastUpdated: Long = com.antigravity.healthagent.utils.TimeManager.currentTimeMillis()
) {
    @com.google.firebase.firestore.DocumentId
    @androidx.room.Ignore
    var cloudId: String? = null
    /**
     * Generates a stable natural key for synchronization and deduplication.
     * WARNING: Any modification to the fields used in this key (agentName, agentUid, data, block, street, number, etc.)
     * will break synchronization by changing the document ID in Firestore, leading to duplicates.
     */
    fun generateNaturalKey(): String {
        if (uuid.isNotBlank()) return uuid
        
        val normalizedDate = data.toDashDate()
        
        // Uniqueness is guaranteed by agentUid + normalizedAgent + date + address details + visitSegment.
        return "${agentUid}_${agentName.normalize()}_${normalizedDate}_${address.generateAddressSignature()}_${visitSegment}".uppercase()
    }

    /**
     * Generates a "Logical Identity Key" that remains stable even if street names are corrected
     * or visit segments shift. Used for deduplication and healing during sync.
     */
    fun generateIdentityKey(): String {
        if (uuid.isNotBlank()) return uuid
        
        val normalizedDate = data.toDashDate()
        
        // Identity is Agent(UID) + Day + Address Signature (excluding segment)
        return "${agentUid}_${normalizedDate}_${address.generateAddressSignature()}".uppercase()
    }

    /**
     * Physical dedup key: NEVER uses uuid. Two inserts producing the same key for the
     * same agent+day represent the same house (same number/sequence/complement on the
     * same street/block/bairro). Used by the duplicate guards and the in-flight merge
     * so that quickly re-added houses (which get fresh UUIDs) cannot create duplicates.
     * Sync identity (uuid) is intentionally NOT used here.
     */
    fun generatePhysicalKey(): String {
        if (uuid.isNotBlank()) return uuid
        val normalizedDate = data.toDashDate()
        return "${agentUid}_${normalizedDate}_${address.generateAddressSignature()}".uppercase()
    }




    @get:com.google.firebase.firestore.Exclude
    val hasAnyTreatment: Boolean 
        get() = treatment.hasAnyTreatment

    @get:com.google.firebase.firestore.Exclude
    val isAddressComplete: Boolean
        get() = address.isComplete
}
