package dev.firetvonwatch.domain.model

/**
 * Keys the remote can send. [keyCode] is the Android `KeyEvent.KEYCODE_*` value that the
 * Fire TV's `input keyevent` command understands.
 */
enum class RemoteKey(val keyCode: Int) {
    DPAD_UP(19),
    DPAD_DOWN(20),
    DPAD_LEFT(21),
    DPAD_RIGHT(22),
    SELECT(23),
    BACK(4),
    HOME(3),
    MENU(82),
    PLAY_PAUSE(85),
    REWIND(89),
    FAST_FORWARD(90),
    /** Volume keys only have an effect when the Fire TV controls the TV/AVR via HDMI-CEC. */
    VOLUME_UP(24),
    VOLUME_DOWN(25),
    MUTE(164),
    SLEEP(223),
    WAKE_UP(224),
    ENTER(66),
    DELETE(67),
    SPACE(62),
}
