package com.antigravity.healthagent.data.local.model

import androidx.room.*
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
            unique = true
        ),
        Index(value = ["data", "agentUid"])
    ]
)
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
        val normalizedDate = data.toDashDate()
        
        // Uniqueness is guaranteed by agentUid + normalizedAgent + date + address details + visitSegment.
        return "${agentUid}_${agentName.normalize()}_${normalizedDate}_${address.generateAddressSignature()}_${visitSegment}".uppercase()
    }

    /**
     * Generates a "Logical Identity Key" that remains stable even if street names are corrected
     * or visit segments shift. Used for deduplication and healing during sync.
     */
    fun generateIdentityKey(): String {
        val normalizedDate = data.toDashDate()
        
        // Identity is Agent(UID) + Day + Address Signature (excluding segment)
        return "${agentUid}_${normalizedDate}_${address.generateAddressSignature()}".uppercase()
    }




    @get:com.google.firebase.firestore.Exclude
    val hasAnyTreatment: Boolean 
        get() = treatment.hasAnyTreatment

    @get:com.google.firebase.firestore.Exclude
    val isAddressComplete: Boolean
        get() = address.isComplete
}
