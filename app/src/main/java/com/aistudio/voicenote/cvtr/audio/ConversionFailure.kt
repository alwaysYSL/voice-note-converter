package com.aistudio.voicenote.cvtr.audio

enum class ConversionErrorCode {
    OPUS_ENCODER_UNAVAILABLE,
    OPUS_CONFIGURE_FAILED,
    DECODER_UNAVAILABLE,
    FOREGROUND_START_FAILED,
    INPUT_UNREADABLE,
    STORAGE_WRITE_FAILED,
    OUTPUT_INVALID,
    UNKNOWN
}

enum class ConversionStage {
    COPY_INPUT,
    START_FOREGROUND,
    OPEN_EXTRACTOR,
    CREATE_DECODER,
    CREATE_ENCODER,
    DECODE,
    ENCODE,
    WRITE_OGG,
    SAVE_MEDIASTORE,
    INSERT_HISTORY,
    UNKNOWN
}

class ConversionPipelineException(
    val errorCode: ConversionErrorCode,
    val stage: ConversionStage,
    val safeMessage: String,
    cause: Throwable? = null
) : Exception(safeMessage, cause)

internal data class ConversionFailureDetails(
    val code: ConversionErrorCode,
    val stage: ConversionStage,
    val message: String
)

internal fun Throwable.toConversionFailureDetails(
    fallbackStage: ConversionStage = ConversionStage.UNKNOWN
): ConversionFailureDetails {
    if (this is ConversionPipelineException) {
        return ConversionFailureDetails(errorCode, stage, safeMessage)
    }
    if (this is NoAudioTrackException) {
        return ConversionFailureDetails(
            ConversionErrorCode.INPUT_UNREADABLE,
            ConversionStage.OPEN_EXTRACTOR,
            "File tidak memiliki track audio. Pilih file audio atau video dengan suara."
        )
    }
    if (this is UnsupportedAudioFormatException) {
        return ConversionFailureDetails(
            ConversionErrorCode.INPUT_UNREADABLE,
            ConversionStage.OPEN_EXTRACTOR,
            "Format media tidak dapat dibaca. Pilih file dengan format lain."
        )
    }
    val messageText = message.orEmpty().lowercase()
    val code = when {
        "decoder" in messageText -> ConversionErrorCode.DECODER_UNAVAILABLE
        "storage" in messageText || "mediastore" in messageText ->
            ConversionErrorCode.STORAGE_WRITE_FAILED
        "opus" in messageText || "encoder" in messageText ->
            ConversionErrorCode.OPUS_CONFIGURE_FAILED
        else -> ConversionErrorCode.UNKNOWN
    }
    val safeMessage = when (code) {
        ConversionErrorCode.DECODER_UNAVAILABLE ->
            "Decoder audio tidak tersedia untuk file ini. Coba format audio lain."
        ConversionErrorCode.STORAGE_WRITE_FAILED ->
            "Hasil tidak dapat disimpan. Periksa ruang penyimpanan lalu coba lagi."
        ConversionErrorCode.OPUS_CONFIGURE_FAILED,
        ConversionErrorCode.OPUS_ENCODER_UNAVAILABLE ->
            "Encoder Opus gagal dimulai. Restart aplikasi lalu coba lagi."
        else -> "Konversi gagal pada tahap ${fallbackStage.name.lowercase()}. Coba file lain."
    }
    return ConversionFailureDetails(code, fallbackStage, safeMessage)
}
