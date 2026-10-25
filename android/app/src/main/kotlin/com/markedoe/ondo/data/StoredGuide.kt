package com.markedoe.ondo.data

import com.markedoe.ondo.core.model.EpgData
import kotlinx.serialization.Serializable

/**
 * A playlist's guide as it was last downloaded and read, kept on the device: the next launch shows it at once instead
 * of downloading it again (as on Apple devices). [sources] tells which guide it is: another address, or channels the
 * catalogue did not have, and it is downloaded again.
 */
@Serializable
data class StoredGuide(val sources: String, val data: EpgData)
