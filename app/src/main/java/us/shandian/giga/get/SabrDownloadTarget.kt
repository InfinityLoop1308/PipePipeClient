package us.shandian.giga.get

import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat
import java.io.File
import java.util.TreeMap

internal data class SabrDownloadTarget(
    val resourceIndex: Int,
    val recovery: MissionRecoveryInfo,
    val format: YoutubeSabrFormat,
    val file: File,
    var nextWriteSequence: Int = 1,
    var initializationWritten: Boolean = false,
    val pending: TreeMap<Int, ByteArray> = TreeMap(),
)
