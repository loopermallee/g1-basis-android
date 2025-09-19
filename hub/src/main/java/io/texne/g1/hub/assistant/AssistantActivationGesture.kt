package io.texne.g1.hub.assistant

import androidx.annotation.StringRes
import io.texne.g1.hub.R

enum class AssistantActivationGesture(@StringRes val labelRes: Int) {
    OFF(R.string.settings_assistant_activation_option_off),
    SINGLE(R.string.settings_assistant_activation_option_single),
    DOUBLE(R.string.settings_assistant_activation_option_double),
    TRIPLE(R.string.settings_assistant_activation_option_triple),
    HOLD(R.string.settings_assistant_activation_option_hold);
}
