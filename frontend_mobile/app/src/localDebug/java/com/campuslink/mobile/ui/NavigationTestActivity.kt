package com.campuslink.mobile.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** Local debug-only host for navigation framework and recreation tests. */
class NavigationTestActivity : ComponentActivity() {
    private var updateNavigation: ((NavigationState) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var navigation by rememberSaveable(stateSaver = NavigationStateSaver) {
                mutableStateOf(NavigationState())
            }
            DisposableEffect(Unit) {
                updateNavigation = { navigation = it }
                onDispose { updateNavigation = null }
            }
            NavigationBackHandler(navigation) { navigation = it }
            Text(navigation.screen.routeKey())
        }
    }

    internal fun navigateTo(state: NavigationState) {
        checkNotNull(updateNavigation)(state)
    }
}
