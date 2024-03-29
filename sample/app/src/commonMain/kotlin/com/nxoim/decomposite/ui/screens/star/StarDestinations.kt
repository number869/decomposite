package com.nxoim.decomposite.ui.screens.star

import kotlinx.serialization.Serializable

@Serializable
sealed interface StarDestinations {
    @Serializable
    data object Home : StarDestinations

    @Serializable
    data object AnotherStar : StarDestinations
}